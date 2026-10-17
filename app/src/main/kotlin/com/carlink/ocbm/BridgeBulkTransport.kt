package com.carlink.ocbm

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.enigy.carlink2.bridge.BridgeContract
import com.enigy.carlink2.bridge.ICarlinkBridge
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [RawBulkTransport] over the sideloaded USB bridge (`android.car.usb.handler`, module `:bridge`).
 *
 * The bridge owns the adapter: it holds the USB permission the platform grants its package silently
 * on every attach, claims the bulk interface, and relays the OCBM byte stream unchanged over a
 * socket. This class is the display-app end of that socket. It knows nothing about OCBM framing —
 * [OcbmClient]'s `Reassembler` does the same job it does over [UsbBulkTransport], which stays as the
 * fallback when no bridge is installed (bench, emulator).
 *
 * Inbound: a read thread copies each chunk from the socket into a reused buffer and hands it to the
 * read handler, like [UsbBulkTransport]. EOF or an IOException means the bridge closed the session
 * (device detached, bridge restarted): the dead handler fires once, off the read thread.
 * Outbound: [writeBulk] is one Binder call per ≤16 KiB chunk, matching the in-app transport's chunking.
 */
class BridgeBulkTransport(
    private val bridge: ICarlinkBridge,
    private val stream: ParcelFileDescriptor,
    val deviceName: String,
    private val log: com.carlink.logging.ProbeLog.Logger,
) : RawBulkTransport {
    companion object {
        private const val READ_BUF = 16384
        private const val WRITE_CHUNK = 16384
        private const val WRITE_TIMEOUT_MS = 2000

        /** The OCBM adapter if attached, by VID/PID — readable without USB permission. */
        fun findAdapter(context: Context): UsbDevice? {
            val usb = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
            return usb.deviceList.values.firstOrNull {
                it.vendorId == UsbBulkTransport.VID_CARLINKIT && it.productId == UsbBulkTransport.PID_OCBM
            }
        }
    }

    private val input = FileInputStream(stream.fileDescriptor)
    private val running = AtomicBoolean(false)
    private val deadAnnounced = AtomicBoolean(false)

    @Volatile private var readThread: Thread? = null

    @Volatile private var handler: ((ByteArray, Int) -> Unit)? = null

    @Volatile private var onDead: (() -> Unit)? = null
    private val writeLock = Object()

    @Volatile var bytesRead: Long = 0
        private set

    @Volatile var reads: Long = 0
        private set

    @Volatile var writeFailures = 0
        private set

    @Volatile var lastError: String? = null
        private set

    override fun setReadHandler(handler: (ByteArray, Int) -> Unit) {
        this.handler = handler
    }

    override fun setDeadHandler(handler: () -> Unit) {
        onDead = handler
    }

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread({ readLoop() }, "ocbm-bridge-read")
        t.isDaemon = true
        readThread = t
        t.start()
    }

    private fun readLoop() {
        val buf = ByteArray(READ_BUF)
        var reason = "stopped"
        try {
            while (running.get()) {
                val n = input.read(buf, 0, buf.size)
                if (n < 0) {
                    reason = "bridge closed the session (EOF)"
                    break
                }
                bytesRead += n
                reads++
                handler?.invoke(buf, n)
            }
        } catch (e: IOException) {
            reason = "socket read failed: ${e.message}"
        } catch (t: Throwable) {
            reason = "read loop error: ${t.javaClass.simpleName}: ${t.message}"
        }
        log.i("bridge read loop ended ($reason) — $reads reads, $bytesRead B")
        val wasRunning = running.getAndSet(false)
        if (wasRunning) {
            lastError = reason
            val h = onDead
            if (h != null && deadAnnounced.compareAndSet(false, true)) {
                Thread({ runCatching { h() } }, "ocbm-dead").apply { isDaemon = true }.start()
            }
        }
    }

    override fun writeBulk(data: ByteArray): Boolean {
        synchronized(writeLock) {
            if (!running.get()) return false
            var off = 0
            while (off < data.size) {
                val len = minOf(WRITE_CHUNK, data.size - off)
                val chunk = if (off == 0 && len == data.size) data else data.copyOfRange(off, off + len)
                val n =
                    try {
                        bridge.write(chunk, WRITE_TIMEOUT_MS)
                    } catch (t: Throwable) {
                        lastError = "bridge.write threw ${t.javaClass.simpleName}: ${t.message}"
                        -1
                    }
                if (n != len) {
                    writeFailures++
                    if (n >= 0) lastError = "bridge wrote $n of $len bytes at offset $off"
                    log.w("write FAILED: $lastError (consecutive=$writeFailures)")
                    return false
                }
                off += n
            }
        }
        writeFailures = 0
        return true
    }

    override fun stop() {
        running.set(false)
        try {
            stream.close() // unblocks a read() parked in readLoop
        } catch (_: IOException) {
        }
        val rt = readThread
        try {
            rt?.join(1500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        readThread = null
        runCatching { bridge.close() }
        log.i("bridge transport closed")
    }

    /** One-line diagnostics from the bridge process (handler config, permission, counters). */
    fun bridgeStatus(): String = runCatching { bridge.status }.getOrElse { "bridge status unavailable: ${it.message}" }

    /** Bridge API this transport was opened against; raw-stream relay requires [BridgeContract.RAW_STREAM_API_VERSION]. */
    fun apiVersion(): Int = runCatching { bridge.apiVersion }.getOrDefault(-1)
}
