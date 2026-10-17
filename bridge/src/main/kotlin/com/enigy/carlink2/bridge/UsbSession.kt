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
        val sink: ParcelFileDescriptor,
    ) {
        @Volatile var running = true
    }

    private val lock = Any()
    private val writeLock = Any()

    @Volatile private var active: Active? = null

    @Volatile var lastError: String = ""
        private set

    @Volatile var lastCloseReason: String = "never opened"
        private set

    val openDeviceName: String? get() = active?.deviceName

    fun hasPermission(deviceName: String?): Boolean {
        val device = deviceName?.let { usbManager.deviceList[it] } ?: return false
        return usbManager.hasPermission(device)
    }

    fun open(deviceName: String?): ParcelFileDescriptor? {
        synchronized(lock) {
            closeLocked("superseded by open($deviceName)")

            val device = deviceName?.let { usbManager.deviceList[it] }
                ?: return fail("$deviceName is not attached")
            if (!usbManager.hasPermission(device)) return fail("no USB permission for $deviceName")

            val connection = usbManager.openDevice(device)
                ?: return fail("openDevice($deviceName) returned null")
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

            val pair =
                try {
                    ParcelFileDescriptor.createSocketPair()
                } catch (e: IOException) {
                    connection.releaseInterface(iface)
                    connection.close()
                    return fail("socket pair: ${e.message}")
                }
            enlargeSendBuffer(pair[0])

            val session = Active(device.deviceName, connection, iface, inEndpoint, outEndpoint, pair[0])
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

    fun write(
        data: ByteArray?,
        timeoutMs: Int,
    ): Int {
        if (data == null || data.size > BridgeContract.MAX_WRITE_BYTES) {
            BridgeStats.writeErrors.incrementAndGet()
            return -1
        }
        val session = active ?: return -1
        // UsbDeviceConnection.bulkTransfer is not documented thread-safe per endpoint, and
        // the display app writes from its heartbeat, mic and UI threads.
        val result =
            synchronized(writeLock) {
                session.connection.bulkTransfer(session.outEndpoint, data, data.size, timeoutMs)
            }
        if (result == data.size) BridgeStats.writes.incrementAndGet() else BridgeStats.writeErrors.incrementAndGet()
        return result
    }

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
            session.sink.close()
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
        val out = FileOutputStream(session.sink.fileDescriptor)
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

    private fun fail(reason: String): ParcelFileDescriptor? {
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
    }
}
