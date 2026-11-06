package com.enigy.carlink2.bridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException

/**
 * One open device at a time: claims its bulk interface, relays the IN endpoint into a socket the
 * display app reads ([pump]), and performs outbound writes on the display app's behalf.
 *
 * API 4 adds a session opened on request ([open] with onRequest): no pump and no socket; the display
 * app asks for each IN transfer with its exact length ([Session.read]), and may clear a halted endpoint
 * or reset USB mass storage's Bulk-Only Transport ([Session.clearHalt], [Session.resetBulkOnly]). A USB
 * stick needs that: it expects an IN only after a command block, of the length the command block gave.
 *
 * API 5 gives each open a [Session] of its own, which [BridgeService] hands the display app as a handle
 * (IUsbBridgeSession). A session's calls act on it only: once it has ended they fail as on a closed
 * session and never reach the session after it, and [Session.endReason] says why it ended. The API ≤ 4
 * calls here ([write], [read], [clearHalt], [resetBulkOnly], [takeTrace], [close]) act on whichever
 * session is open, as they always have.
 *
 * Ending a session never waits out a transfer: it releases the interface first, which makes a transfer
 * in flight on it return -1 at once, and the connection closes when the last transfer has left it
 * ([Session.end]), so no transfer runs on a closed, maybe reused, file descriptor.
 */
internal class UsbSession(
    private val usbManager: UsbManager,
) {
    /**
     * One open device: its connection and claimed interface, and for a streaming session the pump's end
     * of the display app's socket. Open until [end]. Every transfer goes through [transfer], which
     * refuses once the session has ended.
     */
    class Session internal constructor(
        val id: Int,
        val deviceName: String,
        private val connection: UsbDeviceConnection,
        private val iface: UsbInterface,
        private val bulkIn: UsbEndpoint,
        private val bulkOut: UsbEndpoint,
        // The pump's end of the display app's socket; null for a session opened on request, which has no pump.
        private val sink: ParcelFileDescriptor?,
        // The display app's end until it takes it ([takeStream]).
        stream: ParcelFileDescriptor?,
        private val trace: TransferTrace?,
        private val onEnded: ((String) -> Unit)?,
    ) {
        private val guard = Any()
        private val writeLock = Any()
        private val readLock = Any()

        // Transfers on the connection right now; it closes when the last one leaves after the end.
        private var inFlight = 0
        private var connectionClosed = false
        private var stream: ParcelFileDescriptor? = stream

        /** Null while the session is open; afterwards why it ended, one of BridgeContract's END_ values. */
        @Volatile var endReason: String? = null
            private set

        val isOpen: Boolean get() = endReason == null

        val onRequest: Boolean get() = sink == null

        /** The display app's end of a streaming session's socket, once; null after that, on request, or once ended. */
        fun takeStream(): ParcelFileDescriptor? = synchronized(guard) { stream.also { stream = null } }

        fun write(
            data: ByteArray?,
            timeoutMs: Int,
        ): Int {
            if (data == null || data.size > BridgeContract.MAX_WRITE_BYTES) {
                BridgeStats.writeErrors.incrementAndGet()
                return -1
            }
            if (!isOpen) return -1
            val started = System.nanoTime()
            // UsbDeviceConnection.bulkTransfer is not documented thread-safe per endpoint, and
            // the display app writes from its heartbeat, mic and UI threads.
            val result =
                synchronized(writeLock) {
                    transfer(-1) { it.bulkTransfer(bulkOut, data, data.size, boundedTimeout(timeoutMs)) }
                }
            trace?.add("OUT $result of ${data.size} B in ${msSince(started)} ms")
            if (result == data.size) BridgeStats.writes.incrementAndGet() else BridgeStats.writeErrors.incrementAndGet()
            return result
        }

        /**
         * On a session opened on request: IN transfers of at most [BridgeContract.READ_CHUNK_BYTES] until
         * [length] bytes are in, a short packet ends the transfer, or one fails. Returns what came, maybe
         * fewer than asked; null when the first transfer failed, on a streaming session, or once ended.
         */
        fun read(
            length: Int,
            timeoutMs: Int,
        ): ByteArray? {
            if (!onRequest || length <= 0 || length > BridgeContract.MAX_READ_BYTES) return null
            val buf = ByteArray(length)
            var got = 0
            var failed = false
            synchronized(readLock) {
                var more = true
                while (more) {
                    val ask = minOf(BridgeContract.READ_CHUNK_BYTES, length - got)
                    val started = System.nanoTime()
                    val n = transfer(-1) { it.bulkTransfer(bulkIn, buf, got, ask, boundedTimeout(timeoutMs)) }
                    trace?.add("IN $n of $ask B in ${msSince(started)} ms")
                    failed = n < 0
                    if (n > 0) got += n
                    // A short packet ends the transfer, as does a failure.
                    more = n == ask && got < length
                }
            }
            if (got > 0) {
                BridgeStats.messagesIn.incrementAndGet()
                BridgeStats.bytesIn.addAndGet(got.toLong())
            }
            return if (failed && got == 0) null else buf.copyOf(got)
        }

        /** On a session opened on request: CLEAR_FEATURE(ENDPOINT_HALT) on the bulk IN or OUT endpoint; -1 otherwise. */
        fun clearHalt(inbound: Boolean): Int {
            if (!onRequest) return -1
            val endpoint = if (inbound) bulkIn else bulkOut
            val result = transfer(-1) { it.controlTransfer(TO_ENDPOINT, CLEAR_FEATURE, ENDPOINT_HALT, endpoint.address, null, 0, CONTROL_TIMEOUT_MS) }
            trace?.add("CLEAR_FEATURE(ENDPOINT_HALT) 0x${endpoint.address.toString(16)}: $result")
            return result
        }

        /**
         * On a session opened on request: Bulk-Only Transport's reset recovery. Bulk-Only Mass Storage Reset
         * (class request 0xFF to the interface), then CLEAR_FEATURE(ENDPOINT_HALT) on both endpoints.
         */
        fun resetBulkOnly(): Boolean {
            if (!onRequest) return false
            val reset = transfer(-1) { it.controlTransfer(CLASS_TO_INTERFACE, BULK_ONLY_RESET, 0, iface.id, null, 0, CONTROL_TIMEOUT_MS) }
            trace?.add("Bulk-Only Mass Storage Reset (interface ${iface.id}): $reset")
            val clearIn = clearHalt(inbound = true)
            val clearOut = clearHalt(inbound = false)
            return reset >= 0 && clearIn >= 0 && clearOut >= 0
        }

        /** The transfer results recorded since the last call, one per line; kept after the end for the display app's log. */
        fun takeTrace(): String = trace?.take().orEmpty()

        /** The pump's IN transfer of one chunk into [buf]: bytes read, or -1 on a timeout, a failure, or once ended. */
        internal fun pumpRead(buf: ByteArray): Int = transfer(-1) { it.bulkTransfer(bulkIn, buf, buf.size, READ_TIMEOUT_MS) }

        /**
         * End the session with [reason]; false when it had already ended. Releases the interface, which makes a
         * transfer in flight on it return -1, gives the display app EOF on a streaming session's socket, and
         * closes the connection now or, when transfers are still in flight, as the last of them leaves it.
         */
        internal fun end(reason: String): Boolean {
            synchronized(guard) {
                if (endReason != null) return false
                endReason = reason
            }
            runCatching { connection.releaseInterface(iface) }
            // The reason is set first: a display app that reads EOF and asks why gets the answer.
            sink?.let {
                runCatching { Os.shutdown(it.fileDescriptor, OsConstants.SHUT_RDWR) }
                closeQuietly(it)
            }
            takeStream()?.let { closeQuietly(it) }
            synchronized(guard) { if (inFlight == 0) closeConnectionLocked() }
            onEnded?.let { runCatching { it(reason) } }
            return true
        }

        /** [call] on the connection unless the session has ended ([ended] then); the connection outlives every call in flight. */
        private inline fun <T> transfer(
            ended: T,
            call: (UsbDeviceConnection) -> T,
        ): T {
            synchronized(guard) {
                if (endReason != null) return ended
                inFlight++
            }
            try {
                return call(connection)
            } finally {
                synchronized(guard) {
                    inFlight--
                    if (endReason != null && inFlight == 0) closeConnectionLocked()
                }
            }
        }

        private fun closeConnectionLocked() {
            if (connectionClosed) return
            connectionClosed = true
            connection.close()
        }
    }

    private val lock = Any()

    private var nextId = 1

    @Volatile private var active: Session? = null

    @Volatile var lastError: String = ""
        private set

    @Volatile var lastCloseReason: String = "never opened"
        private set

    val openDeviceName: String? get() = active?.let { "${it.deviceName} #${it.id}${if (it.onRequest) " on request" else ""}" }

    fun hasPermission(deviceName: String?): Boolean {
        val device = deviceName?.let { usbManager.deviceList[it] } ?: return false
        return usbManager.hasPermission(device)
    }

    /**
     * Open [deviceName] and claim its bulk interface with force, ending the session before it. [onRequest]:
     * no pump and no socket, nothing read until [Session.read] asks, and [trace] records each transfer.
     * Otherwise the pump relays the IN endpoint into a socket whose other end is [Session.takeStream]'s.
     * [onEnded] runs once, with why, when the session ends. Null on failure, reason in [lastError]; an open
     * that fails before it can claim the device (not attached, no permission, no bulk interface) leaves the
     * open session alone.
     */
    fun open(
        deviceName: String?,
        onRequest: Boolean,
        trace: Boolean,
        onEnded: ((String) -> Unit)? = null,
    ): Session? {
        synchronized(lock) {
            val device = deviceName?.let { usbManager.deviceList[it] } ?: return fail("$deviceName is not attached")
            if (!usbManager.hasPermission(device)) return fail("no USB permission for $deviceName")
            val (iface, bulkIn, bulkOut) = findBulkEndpoints(device) ?: return fail("no interface with bulk IN and OUT on $deviceName")
            val connection = usbManager.openDevice(device) ?: return fail("openDevice($deviceName) returned null")
            val pair =
                if (onRequest) {
                    null
                } else {
                    try {
                        ParcelFileDescriptor.createSocketPair()
                    } catch (e: IOException) {
                        connection.close()
                        return fail("socket pair: ${e.message}")
                    }
                }
            // The open goes ahead: the session before it ends here, which frees the interface to claim.
            active?.let { end(it, BridgeContract.END_SUPERSEDED, "superseded by the open of $deviceName") }
            if (!connection.claimInterface(iface, true)) {
                pair?.forEach { closeQuietly(it) }
                connection.close()
                return fail("claimInterface failed on $deviceName")
            }
            val session =
                Session(
                    nextId++,
                    device.deviceName,
                    connection,
                    iface,
                    bulkIn,
                    bulkOut,
                    sink = pair?.get(0),
                    stream = pair?.get(1),
                    trace = if (onRequest && trace) TransferTrace() else null,
                    onEnded = onEnded,
                )
            active = session
            lastError = ""
            if (pair != null) {
                enlargeSendBuffer(pair[0])
                Thread({ pump(session, pair[0]) }, "Bridge-UsbPump").apply {
                    isDaemon = true
                    start()
                }
            }
            val how = if (onRequest) " on request (trace=$trace)" else ""
            BridgeStats.event("open $deviceName #${session.id}$how")
            Log.i(
                TAG,
                "Opened $deviceName as session ${session.id}$how (interface ${iface.id}, " +
                    "IN=0x${bulkIn.address.toString(16)} OUT=0x${bulkOut.address.toString(16)})",
            )
            return session
        }
    }

    /** End [session] with [reason] ([detail] for the status line and the log) unless it has ended already. */
    fun end(
        session: Session,
        reason: String,
        detail: String = reason,
    ) {
        synchronized(lock) {
            if (active === session) active = null
            if (!session.end(reason)) return
            lastCloseReason = detail
            BridgeStats.event("close ${session.deviceName} #${session.id}: $detail")
            Log.i(TAG, "Closed ${session.deviceName} (session ${session.id}): $detail")
        }
    }

    /** API ≤ 4: [Session.write] on whichever session is open; -1 when none is. */
    fun write(
        data: ByteArray?,
        timeoutMs: Int,
    ): Int = active?.write(data, timeoutMs) ?: -1

    /** API 4: [Session.read] on whichever session is open. */
    fun read(
        length: Int,
        timeoutMs: Int,
    ): ByteArray? = active?.read(length, timeoutMs)

    /** API 4: [Session.clearHalt] on whichever session is open. */
    fun clearHalt(inbound: Boolean): Int = active?.clearHalt(inbound) ?: -1

    /** API 4: [Session.resetBulkOnly] on whichever session is open. */
    fun resetBulkOnly(): Boolean = active?.resetBulkOnly() ?: false

    /** API 4: the open session's transfer results since the last call, one per line. */
    fun takeTrace(): String = active?.takeTrace().orEmpty()

    /** End whichever session is open. */
    fun close(
        reason: String,
        detail: String = reason,
    ) {
        synchronized(lock) { active?.let { end(it, reason, detail) } }
    }

    fun onDetached(deviceName: String?) {
        synchronized(lock) {
            active?.takeIf { deviceName != null && it.deviceName == deviceName }?.let { end(it, BridgeContract.END_DETACHED) }
        }
    }

    /**
     * Carlink3: a RAW relay. Each bulk IN read (up to [BridgeContract.READ_CHUNK_BYTES]) is written
     * to the socket as-is; the bridge knows nothing about OCBM framing — the display app's OCBM
     * client reassembles frames, exactly as it does over a direct USB connection. (Carlink2's
     * bridge parsed the stock 16-byte 0x55AA55AA header here; that framing does not exist on an
     * OCBM adapter.) `messagesIn` now counts chunks. Runs until the session ends, or ends it.
     */
    private fun pump(
        session: Session,
        sink: ParcelFileDescriptor,
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY - 2)
        val out = FileOutputStream(sink.fileDescriptor)
        val buf = ByteArray(BridgeContract.READ_CHUNK_BYTES)
        var reason = BridgeContract.END_PUMP_FAILED
        val detail: String
        try {
            while (session.isOpen) {
                val n = session.pumpRead(buf)
                if (!session.isOpen) return
                if (n < 0) {
                    // Timeout (idle link) or a hard failure; bulkTransfer does not tell them apart.
                    // A device that left ends the session (the detach broadcast), and this loop with it.
                    Thread.sleep(FAILED_READ_BACKOFF_MS)
                    continue
                }
                if (n == 0) continue
                out.write(buf, 0, n)
                BridgeStats.messagesIn.incrementAndGet()
                BridgeStats.bytesIn.addAndGet(n.toLong())
            }
            return
        } catch (e: IOException) {
            reason = BridgeContract.END_STREAM_CLOSED
            detail = "display app stopped reading (${e.message})"
        } catch (e: InterruptedException) {
            detail = "pump interrupted"
        } catch (e: Exception) {
            detail = "pump error: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, detail, e)
        }
        end(session, reason, detail)
    }

    private fun <T> fail(reason: String): T? {
        lastError = reason
        BridgeStats.event("open failed: $reason")
        Log.w(TAG, "open failed: $reason")
        return null
    }

    // Lowest-index interface with both bulk IN and bulk OUT — same rule as the display app.
    private fun findBulkEndpoints(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var inEndpoint: UsbEndpoint? = null
            var outEndpoint: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) inEndpoint = endpoint else outEndpoint = endpoint
            }
            if (inEndpoint != null && outEndpoint != null) return Triple(iface, inEndpoint, outEndpoint)
        }
        return null
    }

    // A full video frame is up to 2 MB; a bigger send buffer lets the pump hand off a frame
    // and get back to the USB endpoint while the display app is still reading it. The kernel
    // caps this at wmem_max, so it is best effort.
    private fun enlargeSendBuffer(fd: ParcelFileDescriptor) {
        try {
            Os.setsockoptInt(fd.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, SEND_BUFFER_BYTES)
        } catch (e: Exception) {
            Log.w(TAG, "SO_SNDBUF not raised: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "Carlink2Bridge"

        // Matches the display app's direct path (AdapterDriver readTimeout).
        const val READ_TIMEOUT_MS = 2_000 // short: a detached device is noticed within a backoff, not 30 s
        const val FAILED_READ_BACKOFF_MS = 200L
        const val SEND_BUFFER_BYTES = 4 * 1024 * 1024
        const val NANOS_PER_MS = 1_000_000L

        // Control requests for USB mass storage's reset recovery (USB 2.0 9.4.1; Bulk-Only Transport 3.1).
        const val TO_ENDPOINT = 0x02 // host to device, standard, endpoint
        const val CLASS_TO_INTERFACE = 0x21 // host to device, class, interface
        const val CLEAR_FEATURE = 0x01
        const val ENDPOINT_HALT = 0
        const val BULK_ONLY_RESET = 0xFF
        const val CONTROL_TIMEOUT_MS = 2_000

        fun msSince(started: Long): Long = (System.nanoTime() - started) / NANOS_PER_MS

        fun closeQuietly(fd: ParcelFileDescriptor) {
            runCatching { fd.close() }
        }
    }
}

/**
 * A transfer's timeout as the bridge passes it to USB: at most [BridgeContract.MAX_TRANSFER_TIMEOUT_MS], and
 * that for 0 or less, which USB would take as "wait forever" and which would then hold a session's end.
 */
internal fun boundedTimeout(timeoutMs: Int): Int =
    if (timeoutMs <= 0) BridgeContract.MAX_TRANSFER_TIMEOUT_MS else minOf(timeoutMs, BridgeContract.MAX_TRANSFER_TIMEOUT_MS)

/**
 * The results of an on-request session's transfers, for the display app's log: the head unit has no
 * adb, so [UsbSession.Session.takeTrace] is the bench's only view of the read side. Also in logcat.
 */
internal class TransferTrace {
    private val lines = ArrayList<String>()
    private var dropped = 0

    fun add(line: String) {
        Log.i(TAG, line)
        synchronized(lines) {
            if (lines.size < MAX_LINES) lines += line else dropped++
        }
    }

    fun take(): String =
        synchronized(lines) {
            val more = if (dropped > 0) listOf("($dropped more not kept)") else emptyList()
            (lines + more).joinToString("\n").also {
                lines.clear()
                dropped = 0
            }
        }

    private companion object {
        const val TAG = "Carlink2Bridge"
        const val MAX_LINES = 400
    }
}
