package com.carlink.ocbm

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.enigy.carlink2.bridge.BridgeContract
import com.enigy.carlink2.bridge.ICarlinkBridge
import com.enigy.carlink2.bridge.IUsbBridgeSession
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [RawBulkTransport] over the sideloaded USB bridge (`android.car.usb.handler`, module `:bridge`).
 *
 * The bridge owns the adapter: it holds the USB permission the platform grants its package silently
 * on every attach, claims the bulk interface, and relays the OCBM byte stream unchanged over a
 * socket. This class is the display-app end of that socket, and the ONLY way Carlink reaches the
 * adapter: the app has no USB access of its own (removed 2026-10-21, as Carlink2 did in [204]), so
 * it never holds a USB permission, never shows a USB dialog, and needs the bridge installed. It knows
 * nothing about OCBM framing; [OcbmClient]'s `Reassembler` handles that.
 *
 * Inbound: a read thread copies each chunk from the socket into a reused buffer and hands it to the
 * read handler. EOF or an IOException means the bridge closed the session
 * (device detached, bridge restarted): the dead handler fires once, off the read thread.
 * Outbound: [writeBulk] is one Binder call per ≤16 KiB chunk, matching the adapter's bulk chunking.
 *
 * The bridge relays ONE session ([BridgeSession]): only the transport opened last ([open]) writes to or
 * closes it; a superseded one's writes are refused and its [stop] leaves the bridge's session alone.
 * From bridge API 5 ([BridgeContract.SESSION_API_VERSION]) the transport works through its session's own
 * handle ([session]): the bridge refuses it too once superseded, and says why the session ended, which
 * the read loop's EOF and a failed write put in the log.
 */
class BridgeBulkTransport private constructor(
    private val bridge: ICarlinkBridge,
    // API 5: this open's own session; null on an older bridge, whose calls act on whichever session is open.
    private val session: IUsbBridgeSession?,
    private val stream: ParcelFileDescriptor,
    val deviceName: String,
    private val log: com.carlink.logging.ProbeLog.Logger,
) : RawBulkTransport {
    companion object {
        private const val READ_BUF = 16384
        private const val WRITE_CHUNK = 16384
        private const val WRITE_TIMEOUT_MS = 2000

        /** The OCBM adapter: CPC200-CCPA running the OCBM userspace (accessory gadget). */
        const val VID_CARLINKIT = 0x1314
        const val PID_OCBM = 0x2d00

        /** Open [deviceName] through [bridge], superseding any session before it; null when the bridge would not. */
        fun open(
            bridge: ICarlinkBridge,
            deviceName: String,
            log: com.carlink.logging.ProbeLog.Logger,
        ): BridgeBulkTransport? =
            synchronized(BridgeSession.lock) {
                val t = runCatching { openLocked(bridge, deviceName, log) }.getOrNull() ?: return null
                t.also { BridgeSession.owner = it }
            }

        /** API 5: a session with a handle of its own, its stream taken from the handle; before that, the one session's socket. */
        private fun openLocked(
            bridge: ICarlinkBridge,
            deviceName: String,
            log: com.carlink.logging.ProbeLog.Logger,
        ): BridgeBulkTransport? {
            if (bridge.apiVersion < BridgeContract.SESSION_API_VERSION) {
                val stream = bridge.open(deviceName) ?: return null
                return BridgeBulkTransport(bridge, null, stream, deviceName, log)
            }
            val session = bridge.openSession(deviceName, false, false, BridgeSession.client) ?: return null
            val stream = runCatching { session.takeStream() }.getOrNull()
            if (stream == null) {
                log.w("the bridge's session on $deviceName ended before its stream was taken: ${endReasonOf(session) ?: "no reason given"}")
                runCatching { session.close() }
                return null
            }
            return BridgeBulkTransport(bridge, session, stream, deviceName, log)
        }

        /** Why the bridge ended [session], or why it can't say; null while the session is open. */
        private fun endReasonOf(session: IUsbBridgeSession): String? = runCatching { session.endReason }.getOrElse { "the bridge didn't answer (${it.javaClass.simpleName})" }

        /** The read loop's EOF, with why the bridge ended [session] when it says (API 5). */
        private fun eofReason(session: IUsbBridgeSession?): String = "bridge closed the session (EOF${session?.let { endReasonOf(it) }?.let { ": $it" }.orEmpty()})"

        /** The OCBM adapter if attached, by VID/PID — readable without USB permission. */
        fun findAdapter(context: Context): UsbDevice? {
            val usb = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
            return usb.deviceList.values.firstOrNull {
                it.vendorId == VID_CARLINKIT && it.productId == PID_OCBM
            }
        }
    }

    private val input = FileInputStream(stream.fileDescriptor)
    private val running = AtomicBoolean(false)
    private val deadAnnounced = AtomicBoolean(false)

    @Volatile private var readThread: Thread? = null

    @Volatile private var handler: ((ByteArray, Int) -> Unit)? = null

    @Volatile private var onDead: (() -> Unit)? = null

    @Volatile private var supersededLogged = false

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
                    reason = eofReason(session)
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
        synchronized(BridgeSession.lock) {
            if (!running.get()) return false
            if (BridgeSession.owner !== this) {
                lastError = "superseded: the bridge's session belongs to another device now"
                if (!supersededLogged) log.w("write refused on $deviceName: $lastError")
                supersededLogged = true
                return false
            }
            var off = 0
            while (off < data.size) {
                val len = minOf(WRITE_CHUNK, data.size - off)
                val chunk = if (off == 0 && len == data.size) data else data.copyOfRange(off, off + len)
                var threw = false
                val n =
                    try {
                        session?.write(chunk, WRITE_TIMEOUT_MS) ?: bridge.write(chunk, WRITE_TIMEOUT_MS)
                    } catch (t: Throwable) {
                        lastError = "bridge.write threw ${t.javaClass.simpleName}: ${t.message}"
                        threw = true
                        -1
                    }
                if (n != len) {
                    writeFailures++
                    if (n >= 0) {
                        lastError = "bridge wrote $n of $len bytes at offset $off"
                    } else if (!threw) {
                        // A timeout or USB failure, or (API 5) a session the bridge has ended: say which.
                        lastError = sessionEndReason()?.let { "the bridge ended the session: $it" } ?: "bridge write failed (a timeout or a USB error)"
                    }
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
        val mine =
            synchronized(BridgeSession.lock) {
                (BridgeSession.owner === this).also {
                    if (it) BridgeSession.owner = null
                    // Its own session (API 5) is always its own to close; an older bridge's one session only while it owns it.
                    val own = session
                    if (own != null) {
                        runCatching { own.close() }
                    } else if (it) {
                        runCatching { bridge.close() }
                    }
                }
            }
        val how =
            when {
                session != null -> " (its session: ${sessionEndReason() ?: "still open"})"
                mine -> ""
                else -> " (superseded: the bridge's session was left alone)"
            }
        log.i("bridge transport closed$how")
    }

    /** This end's counters only: no call into the bridge, which may be the part that is stuck. */
    override fun diagnostics(): String = "bridge socket: $reads reads, $bytesRead B, $writeFailures write failures in a row, last error ${lastError ?: "none"}"

    /** One-line diagnostics from the bridge process (handler config, permission, counters). */
    fun bridgeStatus(): String = runCatching { bridge.status }.getOrElse { "bridge status unavailable: ${it.message}" }

    /** API 5: why the bridge ended this transport's session (a BridgeContract END_ value); null while it is open, or on an older bridge. */
    fun sessionEndReason(): String? = session?.let { endReasonOf(it) }

    /** Bridge API this transport was opened against; raw-stream relay requires [BridgeContract.RAW_STREAM_API_VERSION]. */
    fun apiVersion(): Int = runCatching { bridge.apiVersion }.getOrDefault(-1)
}
