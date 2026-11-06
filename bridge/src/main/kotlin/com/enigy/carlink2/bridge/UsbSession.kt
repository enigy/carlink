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
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * One open CPC200 at a time: claims the bulk interface, forwards inbound messages into a
 * socket the display app reads, and performs outbound writes on the display app's behalf.
 *
 * Streaming sessions relay raw chunks. A session opened on request has no pump or socket: the app
 * asks for each IN transfer with its exact length ([read]), and may clear a halted endpoint or reset
 * USB mass storage's Bulk-Only Transport ([clearHalt], [resetBulkOnly]). A USB stick needs that: it
 * expects an IN only after a command block, of the length the command block gave.
 */
internal class UsbSession(
    private val usbManager: UsbManager,
) {
    interface Owner {
        val isAlive: Boolean get() = true
        fun ended(reason: String) = Unit
    }

    data class LegacyOwner(val uid: Int, val pid: Int) : Owner

    class Opened(val stream: ParcelFileDescriptor?)

    private class Active(
        val owner: Owner,
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
        // IN and OUT can run in parallel. Teardown waits for both before reusing the interface.
        val transfers = ReentrantReadWriteLock()

        inline fun transfer(call: () -> Int): Int = transfers.read {
            if (running) call() else -1
        }
    }

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

    fun open(owner: Owner, deviceName: String?, onRequest: Boolean = false, trace: Boolean = false): Opened? {
        synchronized(lock) {
            if (!owner.isAlive) return null
            if (owner is LegacyOwner && active?.let { it.owner != owner } == true) {
                return fail("USB session owned by another client")
            }
            val device = deviceName?.let { usbManager.deviceList[it] } ?: return fail("$deviceName is not attached")
            if (!usbManager.hasPermission(device)) return fail("no USB permission for $deviceName")
            val (iface, inEndpoint, outEndpoint) =
                findBulkEndpoints(device) ?: return fail("no interface with bulk IN and OUT on $deviceName")
            val connection = usbManager.openDevice(device) ?: return fail("openDevice($deviceName) returned null")
            val pair = if (onRequest) null else
                try {
                    ParcelFileDescriptor.createSocketPair()
                } catch (e: IOException) {
                    connection.close()
                    return fail("socket pair: ${e.message}")
                }

            closeLocked(BridgeContract.END_HANDOFF)
            if (!connection.claimInterface(iface, true)) {
                pair?.forEach(::closeSocket)
                connection.close()
                return fail("claimInterface failed on $deviceName")
            }
            val sink = pair?.get(0)
            val session = Active(
                owner, device.deviceName, connection, iface, inEndpoint, outEndpoint, sink,
                trace = if (onRequest && trace) TransferTrace() else null,
            )
            active = session
            if (sink != null) {
                enlargeSendBuffer(sink)
                Thread({ pump(session) }, "Bridge-UsbPump").apply {
                    isDaemon = true
                    start()
                }
            }
            lastError = ""
            BridgeStats.event("open $deviceName${if (onRequest) " on request" else ""}")
            Log.i(TAG, "Opened $deviceName (interface ${iface.id}, IN=${inEndpoint.address}, OUT=${outEndpoint.address})")
            return Opened(pair?.get(1))
        }
    }

    fun write(
        owner: Owner,
        data: ByteArray?,
        timeoutMs: Int,
    ): Int {
        if (data == null || data.size > BridgeContract.MAX_WRITE_BYTES) {
            BridgeStats.writeErrors.incrementAndGet()
            return -1
        }
        val session = active?.takeIf { it.owner == owner } ?: return -1
        val started = System.nanoTime()
        // UsbDeviceConnection.bulkTransfer is not documented thread-safe per endpoint, and
        // the display app writes from its heartbeat, mic and UI threads.
        val result =
            synchronized(writeLock) {
                session.transfer {
                    session.connection.bulkTransfer(session.outEndpoint, data, data.size, boundedTimeout(timeoutMs))
                }
            }
        session.trace?.add("OUT $result of ${data.size} B in ${msSince(started)} ms")
        if (result == data.size) BridgeStats.writes.incrementAndGet() else BridgeStats.writeErrors.incrementAndGet()
        // A partial write tears the streaming protocol. Mass storage handles stalls with its own reset.
        if (result != data.size && session.sink != null) {
            closeOwn(session, transferFailureReason(session))
        }
        return result
    }

    /**
     * API 4, on a session opened on request: IN transfers of at most [BridgeContract.READ_CHUNK_BYTES]
     * until [length] bytes are in, a short packet ends the transfer, or one fails. Returns what came,
     * maybe fewer than asked; null when the first transfer failed or this is no on-request session.
     */
    fun read(
        owner: Owner,
        length: Int,
        timeoutMs: Int,
    ): ByteArray? {
        val session = active?.takeIf { it.owner == owner && it.sink == null } ?: return null
        if (length <= 0 || length > BridgeContract.MAX_READ_BYTES) return null
        val buf = ByteArray(length)
        var got = 0
        var failed = false
        synchronized(readLock) {
            var more = true
            while (more) {
                if (active !== session) return null
                val ask = minOf(BridgeContract.READ_CHUNK_BYTES, length - got)
                val started = System.nanoTime()
                val n = session.transfer {
                    session.connection.bulkTransfer(session.inEndpoint, buf, got, ask, boundedTimeout(timeoutMs))
                }
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
    fun clearHalt(owner: Owner, inEndpoint: Boolean): Int {
        val session = active?.takeIf { it.owner == owner && it.sink == null } ?: return -1
        return clearHalt(session, inEndpoint)
    }

    private fun clearHalt(session: Active, inEndpoint: Boolean): Int {
        val endpoint = if (inEndpoint) session.inEndpoint else session.outEndpoint
        val result = session.transfer {
            session.connection.controlTransfer(TO_ENDPOINT, CLEAR_FEATURE, ENDPOINT_HALT, endpoint.address, null, 0, CONTROL_TIMEOUT_MS)
        }
        session.trace?.add("CLEAR_FEATURE(ENDPOINT_HALT) 0x${endpoint.address.toString(16)}: $result")
        return result
    }

    /**
     * API 4, on a session opened on request: Bulk-Only Transport's reset recovery. Bulk-Only Mass Storage
     * Reset (class request 0xFF to the interface), then CLEAR_FEATURE(ENDPOINT_HALT) on both endpoints.
     */
    fun resetBulkOnly(owner: Owner): Boolean {
        val session = active?.takeIf { it.owner == owner && it.sink == null } ?: return false
        val reset = session.transfer {
            session.connection.controlTransfer(CLASS_TO_INTERFACE, BULK_ONLY_RESET, 0, session.iface.id, null, 0, CONTROL_TIMEOUT_MS)
        }
        session.trace?.add("Bulk-Only Mass Storage Reset (interface ${session.iface.id}): $reset")
        val clearIn = clearHalt(session, inEndpoint = true)
        val clearOut = clearHalt(session, inEndpoint = false)
        return reset >= 0 && clearIn >= 0 && clearOut >= 0
    }

    /** API 4: the transfer results recorded since the last call, one per line. */
    fun takeTrace(owner: Owner): String = active?.takeIf { it.owner == owner }?.trace?.take().orEmpty()

    fun close(reason: String, owner: Owner? = null) {
        synchronized(lock) {
            if (owner == null || active?.owner == owner) closeLocked(reason)
        }
    }

    fun onDetached(deviceName: String?) {
        synchronized(lock) {
            if (deviceName != null && active?.deviceName == deviceName) closeLocked(BridgeContract.END_DETACHED)
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
        lastCloseReason = reason
        // Publish the end reason before making the old client's socket readable as EOF.
        session.owner.ended(reason)
        // The display app sees EOF on its end of the socket.
        session.sink?.let { sink ->
            runCatching { Os.shutdown(sink.fileDescriptor, OsConstants.SHUT_RDWR) }
            closeSocket(sink)
        }
        session.transfers.write {
            runCatching { session.connection.releaseInterface(session.iface) }
            session.connection.close()
        }
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
        var reason = BridgeContract.END_TRANSFER_FAILED
        try {
            while (session.running) {
                val n = session.transfer {
                    session.connection.bulkTransfer(session.inEndpoint, buf, buf.size, READ_TIMEOUT_MS)
                }
                if (!session.running) break
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
            reason = BridgeContract.END_CLIENT_GONE
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            reason = transferFailureReason(session)
            Log.e(TAG, "USB pump failed", e)
        } finally {
            closeOwn(session, reason)
        }
    }

    private fun closeSocket(fd: ParcelFileDescriptor) {
        runCatching { fd.close() }
    }

    private fun transferFailureReason(session: Active): String =
        if (usbManager.deviceList.containsKey(session.deviceName)) BridgeContract.END_TRANSFER_FAILED
        else BridgeContract.END_DETACHED

    private fun <T> fail(reason: String): T? {
        lastError = reason
        BridgeStats.event("open failed: $reason")
        Log.w(TAG, "open failed: $reason")
        return null
    }

    private fun msSince(started: Long): Long = (System.nanoTime() - started) / NANOS_PER_MS

    // USB's zero timeout waits forever. A generic client must not be able to block a handoff.
    private fun boundedTimeout(timeoutMs: Int): Int =
        if (timeoutMs <= 0) MAX_TRANSFER_TIMEOUT_MS else timeoutMs.coerceAtMost(MAX_TRANSFER_TIMEOUT_MS)

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
        const val MAX_TRANSFER_TIMEOUT_MS = 30_000 // bulk-only mass storage's write-status timeout
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
