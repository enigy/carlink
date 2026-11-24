package com.carlink.ocbm

import com.carlink.logging.ProbeLog
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.net.SocketFactory

/**
 * [RawBulkTransport] over the Wi-Fi link: a TCP connection to the box's port [OcbwLink.PORT] on the phone's hotspot,
 * the OCBM byte stream inside OCBW v1 records ([OcbwLink]). To [OcbmClient] it is the USB bridge's socket by another
 * road: the same unframed stream, the same read and dead handlers, so nothing above this line knows which it has.
 *
 * A vanished box is noticed within [Timing.deadMs] (the plan's dead-link rule, both sides): the box sends an empty record
 * whenever it has sent nothing for a second, and so does this side ([Timing.keepaliveMs]), so a read that sees no bytes
 * for [Timing.deadMs] means the box is gone (the socket's read timeout). A write stuck for [Timing.writeStallMs] means
 * the same; Android sockets have no send timeout, so the watchdog closes the socket, which ends the stuck write. TCP's
 * own keepalive is not relied on. Either way the dead handler fires once, off the read thread, like a bridge EOF, and the
 * manager's reconnect takes over.
 */
class WifiBulkTransport private constructor(
    private val socket: Socket,
    private val session: OcbwLink.Session,
    /** `host:port`, for the log. */
    val peer: String,
    private val log: ProbeLog.Logger,
    private val timing: Timing,
) : RawBulkTransport {
    /** The link's waits; tests pass short ones. */
    data class Timing(
        /** The TCP connect. */
        val connectMs: Int = 4_000,
        /** The OCBW handshake's reads: the box gives a stranger 5 s. */
        val handshakeMs: Int = 5_000,
        /** Nothing sent for this long: send an empty record. */
        val keepaliveMs: Long = 1_000,
        /** No bytes from the box for this long: it is gone. */
        val deadMs: Int = 3_000,
        /** One write stuck for this long: it is gone. */
        val writeStallMs: Long = 3_000,
        /** How often the watchdog looks. */
        val tickMs: Long = 250,
    )

    companion object {
        /** Record plaintext per write: well under the box's 64 KiB, so a heartbeat never waits behind a whole frame. */
        const val WRITE_CHUNK = 16 * 1024
        private const val READ_BUFFER = 64 * 1024
        private const val JOIN_MS = 1_500L
        private const val NANOS_PER_MS = 1_000_000L

        /**
         * Connect to [address] and run the OCBW handshake with [key]. [socketFactory] makes a socket bound to the network
         * the box is on (`Network.socketFactory`). Throws with the reason in its message: no route, refused (no listener),
         * a timeout, the box closing the connection (it refuses a host that way), or [OcbwLink.LinkException] (not a box,
         * or a box with another key).
         */
        fun connect(
            address: InetSocketAddress,
            key: ByteArray,
            log: ProbeLog.Logger,
            socketFactory: SocketFactory = SocketFactory.getDefault(),
            timing: Timing = Timing(),
        ): WifiBulkTransport {
            val s = socketFactory.createSocket()
            var linked = false
            try {
                s.tcpNoDelay = true
                s.connect(address, timing.connectMs)
                s.soTimeout = timing.handshakeMs
                val session = OcbwLink.hostHandshake(s.getInputStream(), s.getOutputStream(), key)
                s.soTimeout = timing.deadMs
                linked = true
                return WifiBulkTransport(s, session, "${address.hostString}:${address.port}", log, timing)
            } finally {
                if (!linked) runCatching { s.close() }
            }
        }
    }

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), READ_BUFFER))
    private val output: OutputStream = socket.getOutputStream()
    private val running = AtomicBoolean(false)
    private val deadAnnounced = AtomicBoolean(false)

    // Every record goes out under this lock, keepalives included: the sealer's counter is the wire order.
    private val writeLock = ReentrantLock()

    @Volatile private var readThread: Thread? = null

    @Volatile private var watchdog: Thread? = null

    @Volatile private var handler: ((ByteArray, Int) -> Unit)? = null

    @Volatile private var onDead: (() -> Unit)? = null

    /** System.nanoTime() when the write in progress began; 0 when none is. */
    @Volatile private var writeSinceNs = 0L

    /** System.nanoTime() of the last record written. */
    @Volatile private var lastWriteNs = System.nanoTime()

    @Volatile var records: Long = 0
        private set

    @Volatile var bytesRead: Long = 0
        private set

    @Volatile var bytesWritten: Long = 0
        private set

    @Volatile var keepalivesSent: Long = 0
        private set

    @Volatile var keepalivesReceived: Long = 0
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
        lastWriteNs = System.nanoTime()
        readThread = Thread({ readLoop() }, "ocbm-wifi-read").apply { isDaemon = true }.also { it.start() }
        watchdog = Thread({ watch() }, "ocbm-wifi-watchdog").apply { isDaemon = true }.also { it.start() }
    }

    private fun readLoop() {
        // Anything not caught below still ends the link: the finally announces it.
        var reason = "the Wi-Fi read loop ended"
        try {
            while (running.get()) {
                val plain = session.rx.read(input)
                if (plain == null) {
                    reason = "the adapter closed the Wi-Fi link"
                    return
                }
                records++
                if (plain.isEmpty()) {
                    keepalivesReceived++
                } else {
                    bytesRead += plain.size
                    handler?.invoke(plain, plain.size)
                }
            }
        } catch (_: SocketTimeoutException) {
            reason = "no bytes from the adapter for ${timing.deadMs} ms"
        } catch (e: OcbwLink.LinkException) {
            reason = "a Wi-Fi record was refused: ${e.message}"
        } catch (e: IOException) {
            reason = "the Wi-Fi read failed: ${e.message}"
        } finally {
            die(reason)
        }
    }

    /** Close a socket whose write is stuck; send a keepalive when nothing went out for a while. */
    private fun watch() {
        while (running.get()) {
            try {
                Thread.sleep(timing.tickMs)
            } catch (_: InterruptedException) {
                return
            }
            val now = System.nanoTime()
            val since = writeSinceNs
            if (since != 0L && now - since > timing.writeStallMs * NANOS_PER_MS) {
                lastError = "a write didn't drain in ${timing.writeStallMs} ms"
                log.w("wifi: $lastError — closing the link")
                die(lastError ?: "write stalled")
                return
            }
            if (now - lastWriteNs >= timing.keepaliveMs * NANOS_PER_MS) sendKeepalive()
        }
    }

    /** An empty record, unless a write holds the line (it is traffic already). */
    private fun sendKeepalive() {
        if (!writeLock.tryLock()) return
        try {
            if (!running.get()) return
            writeSinceNs = System.nanoTime()
            output.write(session.tx.keepalive())
            output.flush()
            keepalivesSent++
            lastWriteNs = System.nanoTime()
        } catch (e: IOException) {
            lastError = lastError ?: "the Wi-Fi keepalive failed: ${e.message}"
            die(lastError ?: "keepalive failed")
        } finally {
            writeSinceNs = 0L
            writeLock.unlock()
        }
    }

    private fun die(reason: String) {
        if (!running.getAndSet(false)) return
        lastError = lastError ?: reason
        log.i("wifi link to $peer ended ($reason): $records records in, $bytesRead B, $bytesWritten B out")
        runCatching { socket.close() }
        val h = onDead
        if (h != null && deadAnnounced.compareAndSet(false, true)) {
            Thread({ runCatching { h() } }, "ocbm-dead").apply { isDaemon = true }.start()
        }
    }

    override fun writeBulk(data: ByteArray): Boolean {
        if (!running.get()) return false
        // Bounded, like the bridge's write: the watchdog closes a stuck socket, which ends the write holding the lock.
        if (!writeLock.tryLock(timing.writeStallMs * 2, TimeUnit.MILLISECONDS)) return false
        try {
            if (!running.get()) return false
            writeSinceNs = System.nanoTime()
            var off = 0
            while (off < data.size) {
                val len = minOf(WRITE_CHUNK, data.size - off)
                output.write(session.tx.seal(data, off, len))
                off += len
            }
            output.flush()
            bytesWritten += data.size
            lastWriteNs = System.nanoTime()
            return true
        } catch (e: IOException) {
            lastError = lastError ?: "the Wi-Fi write failed: ${e.message}"
            log.w("wifi write FAILED: $lastError")
            return false
        } finally {
            writeSinceNs = 0L
            writeLock.unlock()
        }
    }

    override fun stop() {
        running.set(false)
        runCatching { socket.close() } // ends a read parked in readLoop
        listOf(readThread, watchdog).forEach { t ->
            if (t != null && t !== Thread.currentThread()) {
                t.interrupt()
                try {
                    t.join(JOIN_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        readThread = null
        watchdog = null
        log.i("wifi transport to $peer closed ($records records in, $bytesWritten B out)")
    }

    override fun diagnostics(): String =
        "wifi link $peer: $records records ($keepalivesReceived keepalives) in, $bytesRead B in, $bytesWritten B out, " +
            "$keepalivesSent keepalives out, last error ${lastError ?: "none"}"
}
