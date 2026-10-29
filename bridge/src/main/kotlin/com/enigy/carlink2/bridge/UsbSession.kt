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
 * One open CPC200 at a time: claims the bulk interface, forwards inbound messages into a
 * socket the display app reads, and performs outbound writes on the display app's behalf.
 *
 * The pump reads the way the display app's direct-USB path always has — exactly 16 header
 * bytes, then the payload in ≤16 KB chunks — so the adapter sees the same request pattern.
 * It forwards a message only once it is complete, so the socket never holds a torn message
 * and the display app's parser can stay strictly aligned.
 *
 * API 4 adds a session opened on request ([openOnRequest]): no pump and no socket; the display app
 * asks for each IN transfer with its exact length ([read]), and may clear a halted endpoint or reset
 * USB mass storage's Bulk-Only Transport ([clearHalt], [resetBulkOnly]). A USB stick needs that: it
 * expects an IN only after a command block, of the length the command block gave.
 */
internal class UsbSession(
    private val usbManager: UsbManager,
) {
    private class Active(
        val deviceName: String,
        val connection: UsbDeviceConnection,
        val iface: UsbInterface,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint,
        // The display app's socket, fed by the pump; null for a session opened on request, which has neither.
        val sink: ParcelFileDescriptor?,
        val trace: TransferTrace?,
    ) {
        @Volatile var running = true
    }

    private data class Claimed(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val iface: UsbInterface,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint,
    )

    private val lock = Any()
    private val writeLock = Any()
    private val readLock = Any()

    @Volatile private var active: Active? = null

    @Volatile var lastError: String = ""
        private set

    @Volatile var lastCloseReason: String = "never opened"
        private set

    val openDeviceName: String? get() = active?.let { if (it.sink == null) "${it.deviceName} on request" else it.deviceName }

    fun hasPermission(deviceName: String?): Boolean {
        val device = deviceName?.let { usbManager.deviceList[it] } ?: return false
        return usbManager.hasPermission(device)
    }

    fun open(deviceName: String?): ParcelFileDescriptor? {
        synchronized(lock) {
            closeLocked("superseded by open($deviceName)")
            val claimed = claimLocked(deviceName) ?: return null
            val (device, connection, iface, inEndpoint, outEndpoint) = claimed

            val pair =
                try {
                    ParcelFileDescriptor.createSocketPair()
                } catch (e: IOException) {
                    connection.releaseInterface(iface)
                    connection.close()
                    return fail("socket pair: ${e.message}")
                }
            enlargeSendBuffer(pair[0])

            val session = Active(device.deviceName, connection, iface, inEndpoint, outEndpoint, pair[0], trace = null)
            active = session
            Thread({ pump(session) }, "Bridge-UsbPump").apply {
                isDaemon = true
                start()
            }
            lastError = ""
            BridgeStats.event("open $deviceName")
            Log.i(TAG, "Opened $deviceName (IN=0x${inEndpoint.address.toString(16)} OUT=0x${outEndpoint.address.toString(16)})")
            // Returned through AIDL with PARCELABLE_WRITE_RETURN_VALUE, which closes our copy.
            return pair[1]
        }
    }

    /**
     * API 4: open [deviceName] for transfers on request. Like [open] it claims the bulk interface with
     * force, but it starts no pump and makes no socket: nothing is read until [read] asks.
     */
    fun openOnRequest(
        deviceName: String?,
        trace: Boolean,
    ): Boolean {
        synchronized(lock) {
            closeLocked("superseded by openOnRequest($deviceName)")
            val c = claimLocked(deviceName) ?: return false
            active = Active(c.device.deviceName, c.connection, c.iface, c.inEndpoint, c.outEndpoint, sink = null, trace = if (trace) TransferTrace() else null)
            lastError = ""
            BridgeStats.event("open $deviceName on request")
            Log.i(
                TAG,
                "Opened $deviceName on request (interface ${c.iface.id}, IN=0x${c.inEndpoint.address.toString(16)} " +
                    "OUT=0x${c.outEndpoint.address.toString(16)}, trace=$trace)",
            )
            return true
        }
    }

    fun write(
        data: ByteArray?,
        timeoutMs: Int,
    ): Int {
        if (data == null || data.size > BridgeContract.MAX_WRITE_BYTES) {
            BridgeStats.writeErrors.incrementAndGet()
            return -1
        }
        val session = active ?: return -1
        val started = System.nanoTime()
        // UsbDeviceConnection.bulkTransfer is not documented thread-safe per endpoint, and
        // the display app writes from its heartbeat, mic and UI threads.
        val result =
            synchronized(writeLock) {
                session.connection.bulkTransfer(session.outEndpoint, data, data.size, timeoutMs)
            }
        session.trace?.add("OUT $result of ${data.size} B in ${msSince(started)} ms")
        if (result == data.size) BridgeStats.writes.incrementAndGet() else BridgeStats.writeErrors.incrementAndGet()
        return result
    }

    /**
     * API 4, on a session opened on request: IN transfers of at most [BridgeContract.READ_CHUNK_BYTES]
     * until [length] bytes are in, a short packet ends the transfer, or one fails. Returns what came,
     * maybe fewer than asked; null when the first transfer failed or this is no on-request session.
     */
    fun read(
        length: Int,
        timeoutMs: Int,
    ): ByteArray? {
        val session = active?.takeIf { it.sink == null } ?: return null
        if (length <= 0 || length > BridgeContract.MAX_READ_BYTES) return null
        val buf = ByteArray(length)
        var got = 0
        var failed = false
        synchronized(readLock) {
            var more = true
            while (more) {
                val ask = minOf(BridgeContract.READ_CHUNK_BYTES, length - got)
                val started = System.nanoTime()
                val n = session.connection.bulkTransfer(session.inEndpoint, buf, got, ask, timeoutMs)
                session.trace?.add("IN $n of $ask B in ${msSince(started)} ms")
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

    /** API 4, on a session opened on request: CLEAR_FEATURE(ENDPOINT_HALT) on the bulk IN or OUT endpoint. */
    fun clearHalt(inEndpoint: Boolean): Int {
        val session = active?.takeIf { it.sink == null } ?: return -1
        val endpoint = if (inEndpoint) session.inEndpoint else session.outEndpoint
        val result = session.connection.controlTransfer(TO_ENDPOINT, CLEAR_FEATURE, ENDPOINT_HALT, endpoint.address, null, 0, CONTROL_TIMEOUT_MS)
        session.trace?.add("CLEAR_FEATURE(ENDPOINT_HALT) 0x${endpoint.address.toString(16)}: $result")
        return result
    }

    /**
     * API 4, on a session opened on request: Bulk-Only Transport's reset recovery. Bulk-Only Mass Storage
     * Reset (class request 0xFF to the interface), then CLEAR_FEATURE(ENDPOINT_HALT) on both endpoints.
     */
    fun resetBulkOnly(): Boolean {
        val session = active?.takeIf { it.sink == null } ?: return false
        val reset = session.connection.controlTransfer(CLASS_TO_INTERFACE, BULK_ONLY_RESET, 0, session.iface.id, null, 0, CONTROL_TIMEOUT_MS)
        session.trace?.add("Bulk-Only Mass Storage Reset (interface ${session.iface.id}): $reset")
        val clearIn = clearHalt(inEndpoint = true)
        val clearOut = clearHalt(inEndpoint = false)
        return reset >= 0 && clearIn >= 0 && clearOut >= 0
    }

    /** API 4: the transfer results recorded since the last call, one per line. */
    fun takeTrace(): String = active?.trace?.take().orEmpty()

    fun close(reason: String) {
        synchronized(lock) { closeLocked(reason) }
    }

    fun onDetached(deviceName: String?) {
        synchronized(lock) {
            if (deviceName != null && active?.deviceName == deviceName) closeLocked("device detached")
        }
    }

    private fun closeOwn(
        session: Active,
        reason: String,
    ) {
        synchronized(lock) {
            if (active === session) closeLocked(reason)
        }
    }

    private fun closeLocked(reason: String) {
        val session = active ?: return
        active = null
        session.running = false
        try {
            session.connection.releaseInterface(session.iface)
        } catch (_: Exception) {
        }
        // Closing the connection makes a bulkTransfer blocked in the pump return -1.
        session.connection.close()
        // The display app sees EOF on its end of the socket.
        try {
            session.sink?.close()
        } catch (_: IOException) {
        }
        lastCloseReason = reason
        BridgeStats.event("close ${session.deviceName}: $reason")
        Log.i(TAG, "Closed ${session.deviceName}: $reason")
    }

    /**
     * Carlink3: a RAW relay. Each bulk IN read (up to [BridgeContract.READ_CHUNK_BYTES]) is written
     * to the socket as-is; the bridge knows nothing about OCBM framing — the display app's OCBM
     * client reassembles frames, exactly as it does over a direct USB connection. (Carlink2's
     * bridge parsed the stock 16-byte 0x55AA55AA header here; that framing does not exist on an
     * OCBM adapter.) `messagesIn` now counts chunks.
     */
    private fun pump(session: Active) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY - 2)
        val out = FileOutputStream(session.sink?.fileDescriptor ?: return)
        val buf = ByteArray(BridgeContract.READ_CHUNK_BYTES)
        var reason = "pump stopped"
        try {
            while (session.running) {
                val n = session.connection.bulkTransfer(session.inEndpoint, buf, buf.size, READ_TIMEOUT_MS)
                if (n < 0) {
                    // Timeout (idle link) or a hard failure; bulkTransfer does not tell them apart.
                    // closeLocked() flips `running` and closes the connection, so a dead device
                    // exits here on the next iteration rather than spinning.
                    Thread.sleep(FAILED_READ_BACKOFF_MS)
                    continue
                }
                if (n == 0) continue
                out.write(buf, 0, n)
                BridgeStats.messagesIn.incrementAndGet()
                BridgeStats.bytesIn.addAndGet(n.toLong())
            }
        } catch (e: IOException) {
            reason = "display app stopped reading (${e.message})"
        } catch (e: InterruptedException) {
            reason = "pump interrupted"
        } catch (e: Exception) {
            reason = "pump error: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, reason, e)
        } finally {
            closeOwn(session, reason)
        }
    }

    /** Open [deviceName], find its bulk endpoints and claim their interface with force; null (reason in [lastError]) on failure. */
    private fun claimLocked(deviceName: String?): Claimed? {
        val device = deviceName?.let { usbManager.deviceList[it] } ?: return fail("$deviceName is not attached")
        if (!usbManager.hasPermission(device)) return fail("no USB permission for $deviceName")
        val connection = usbManager.openDevice(device) ?: return fail("openDevice($deviceName) returned null")
        val endpoints = findBulkEndpoints(device)
        if (endpoints == null) {
            connection.close()
            return fail("no interface with bulk IN and OUT on $deviceName")
        }
        val (iface, inEndpoint, outEndpoint) = endpoints
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            return fail("claimInterface failed on $deviceName")
        }
        return Claimed(device, connection, iface, inEndpoint, outEndpoint)
    }

    private fun <T> fail(reason: String): T? {
        lastError = reason
        BridgeStats.event("open failed: $reason")
        Log.w(TAG, "open failed: $reason")
        return null
    }

    private fun msSince(started: Long): Long = (System.nanoTime() - started) / NANOS_PER_MS

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
    }
}

/**
 * The results of an on-request session's transfers, for the display app's log: the head unit has no
 * adb, so [UsbSession.takeTrace] is the bench's only view of the read side. Also in logcat.
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
