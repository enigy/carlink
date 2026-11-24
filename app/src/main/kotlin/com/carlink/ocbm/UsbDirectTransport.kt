package com.carlink.ocbm

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.carlink.logging.ProbeLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB direct: [RawBulkTransport] over Android's own USB host API, in Carlink's process, for head units where Android
 * grants Carlink the adapter itself ([com.carlink.usb.DirectUsbPermission]). It came back on 24 Nov (removed in
 * a55158c, when the bridge became the only way) with what the bridge's transfer loop has learned since (bridge
 * UsbSession): every transfer bounded; a read that returns nothing (an idle link, or a failure: bulkTransfer can't tell)
 * backs off and goes on; a failed write fails that write, never the stream; the adapter leaving ends it (the detach
 * broadcast, CarlinkManager.onUsbDeviceDetached); and [stop] releases the interface, which ends a transfer in flight,
 * closing the connection only once the last one has left it.
 *
 * The adapter enumerates as 1314:2d00, a raw byte pipe: the lowest-index interface with a bulk IN and OUT pair, no
 * handshake of its own; all framing is OCBM's ([OcbmClient]'s Reassembler).
 */
class UsbDirectTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    val deviceName: String,
    private val log: ProbeLog.Logger,
) : RawBulkTransport {
    companion object {
        private const val READ_BUF = 16 * 1024
        private const val WRITE_CHUNK = 16 * 1024

        /** Short: a detached device is noticed within a backoff, not after a long transfer (the bridge's 2 s). */
        private const val READ_TIMEOUT_MS = 2_000
        private const val WRITE_TIMEOUT_MS = 2_000
        private const val JOIN_MS = 1_500L

        /** Open and claim [device] (permission already granted); null, with why in the log, when it can't. */
        fun open(
            usb: UsbManager,
            device: UsbDevice,
            log: ProbeLog.Logger,
        ): UsbDirectTransport? {
            val (iface, bulkIn, bulkOut) =
                bulkEndpoints(device) ?: return null.also { log.w("${device.deviceName}: no interface with a bulk IN and OUT pair") }
            val connection = usb.openDevice(device) ?: return null.also { log.w("openDevice(${device.deviceName}) returned null: no permission?") }
            if (!connection.claimInterface(iface, true)) {
                log.w("claimInterface failed on ${device.deviceName}: another process holds it?")
                connection.close()
                return null
            }
            log.i("claimed ${device.deviceName} interface ${iface.id} IN=0x%02x OUT=0x%02x".format(bulkIn.address, bulkOut.address))
            return UsbDirectTransport(connection, iface, bulkIn, bulkOut, device.deviceName, log)
        }

        /** The lowest-index interface with both a bulk IN and a bulk OUT endpoint: the bridge's rule. */
        private fun bulkEndpoints(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null
                for (j in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(j)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
                if (inEp != null && outEp != null) return Triple(itf, inEp, outEp)
            }
            return null
        }
    }

    private val running = AtomicBoolean(false)
    private val guard = Any()
    private var inFlight = 0
    private var ended = false
    private var closed = false

    @Volatile private var readThread: Thread? = null

    @Volatile private var handler: ((ByteArray, Int) -> Unit)? = null

    @Volatile private var endReason: String? = null

    @Volatile var bytesRead: Long = 0
        private set

    @Volatile var writeFailures = 0
        private set

    override fun setReadHandler(handler: (ByteArray, Int) -> Unit) {
        this.handler = handler
    }

    // The adapter leaving is the detach broadcast's to report (CarlinkManager.onUsbDeviceDetached), as with the bridge,
    // whose pump also never ends on a failed read.
    override fun setDeadHandler(handler: () -> Unit) = Unit

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        readThread = Thread({ pump() }, "ocbm-usb-direct-read").apply { isDaemon = true }.also { it.start() }
    }

    private fun pump() {
        val buf = ByteArray(READ_BUF)
        while (running.get()) {
            val n = transfer(-1) { it.bulkTransfer(bulkIn, buf, buf.size, READ_TIMEOUT_MS) }
            when {
                !running.get() -> return
                n > 0 -> deliver(buf, n)
                n < 0 && !backOff() -> return
            }
        }
    }

    private fun deliver(
        buf: ByteArray,
        n: Int,
    ) {
        bytesRead += n
        runCatching { handler?.invoke(buf, n) }.onFailure { log.e("read handler threw ${it.javaClass.simpleName}: ${it.message}") }
    }

    override fun writeBulk(data: ByteArray): Boolean {
        if (!running.get()) return false
        var off = 0
        while (off < data.size) {
            val len = minOf(WRITE_CHUNK, data.size - off)
            val n = transfer(-1) { it.bulkTransfer(bulkOut, data, off, len, WRITE_TIMEOUT_MS) }
            if (n != len) {
                writeFailures++
                log.w("write FAILED: $n of $len bytes at offset $off (consecutive=$writeFailures)")
                return false
            }
            off += n
        }
        writeFailures = 0
        return true
    }

    /** [call] on the connection unless the transport has ended ([fallback] then); the connection outlives every call in flight. */
    private inline fun transfer(
        fallback: Int,
        call: (UsbDeviceConnection) -> Int,
    ): Int {
        synchronized(guard) {
            if (ended) return fallback
            inFlight++
        }
        try {
            return call(connection)
        } finally {
            synchronized(guard) {
                inFlight--
                if (ended && inFlight == 0) closeLocked()
            }
        }
    }

    private fun closeLocked() {
        if (closed) return
        closed = true
        connection.close()
    }

    override fun stop() {
        running.set(false)
        synchronized(guard) {
            if (ended) return
            ended = true
            endReason = "stopped"
        }
        // Releasing the interface makes a transfer in flight return; the connection closes as the last one leaves.
        runCatching { connection.releaseInterface(iface) }
        synchronized(guard) { if (inFlight == 0) closeLocked() }
        readThread?.takeIf { it !== Thread.currentThread() }?.let {
            it.interrupt()
            runCatching { it.join(JOIN_MS) }
        }
        readThread = null
        log.i("USB direct transport on $deviceName closed ($bytesRead B read)")
    }

    override fun diagnostics(): String = "usb direct $deviceName: $bytesRead B in, $writeFailures write failures in a row, ended ${endReason ?: "no"}"
}

/** The pause after a read that got nothing (idle, or failed: bulkTransfer can't tell); false when interrupted (stopping). */
private fun backOff(): Boolean =
    try {
        Thread.sleep(FAILED_READ_BACKOFF_MS)
        true
    } catch (_: InterruptedException) {
        false
    }

private const val FAILED_READ_BACKOFF_MS = 200L
