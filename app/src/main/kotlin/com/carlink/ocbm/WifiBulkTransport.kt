package com.carlink.ocbm

import com.carlink.logging.ProbeLog
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
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
 * manager's reconnect takes over. How the link ended, and which side ended it, is [howItEnded] ([WifiLinkEnd]): the line
 * the session's error carries into the drive log.
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
        ): WifiBulkTransport = open(address, log, socketFactory, timing) { i, o -> OcbwLink.hostHandshake(i, o, key) }

        /**
         * The same link after another [handshake] that ends in OCBW's records: the set-up network's ([OcbsSetup]), whose
         * keys come from P-256 rather than a link key. Throws as [connect] does.
         */
        fun open(
            address: InetSocketAddress,
            log: ProbeLog.Logger,
            socketFactory: SocketFactory = SocketFactory.getDefault(),
            timing: Timing = Timing(),
            handshake: (java.io.InputStream, OutputStream) -> OcbwLink.Session,
        ): WifiBulkTransport {
            val s = socketFactory.createSocket()
            var linked = false
            try {
                s.tcpNoDelay = true
                s.connect(address, timing.connectMs)
                s.soTimeout = timing.handshakeMs
                val session = handshake(s.getInputStream(), s.getOutputStream())
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

    @Volatile private var keepaliveThread: Thread? = null

    @Volatile private var handler: ((ByteArray, Int) -> Unit)? = null

    @Volatile private var onDead: (() -> Unit)? = null

    /** System.nanoTime() when the write in progress began; 0 when none is. */
    @Volatile private var writeSinceNs = 0L

    /** System.nanoTime() of the last record written. */
    @Volatile private var lastWriteNs = System.nanoTime()

    /** System.nanoTime() of the last record read, keepalives included; 0 before the first. */
    @Volatile private var lastReadNs = 0L

    /** System.nanoTime() at [start]. */
    @Volatile private var startedNs = 0L

    /** How the link ended by itself ([die]); null while it runs, and after an orderly [stop]. */
    @Volatile private var endLine: String? = null

    override val howItEnded: String? get() = endLine

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
        startedNs = System.nanoTime()
        lastWriteNs = startedNs
        readThread = Thread({ readLoop() }, "ocbm-wifi-read").apply { isDaemon = true }.also { it.start() }
        watchdog = Thread({ watch() }, "ocbm-wifi-watchdog").apply { isDaemon = true }.also { it.start() }
        keepaliveThread = Thread({ keepAlive() }, "ocbm-wifi-keepalive").apply { isDaemon = true }.also { it.start() }
    }

    private fun readLoop() {
        // Anything not caught below still ends the link: the finally announces it.
        var end: WifiLinkEnd? = null
        // Around the handler's call: its own failure is Carlink's, and is not to be read as the socket's.
        var handling = false
        try {
            while (running.get()) {
                val plain = session.rx.read(input)
                if (plain == null) {
                    end = WifiLinkEnd.endOfStream()
                    return
                }
                lastReadNs = System.nanoTime()
                records++
                if (plain.isEmpty()) {
                    keepalivesReceived++
                } else {
                    bytesRead += plain.size
                    handling = true
                    handler?.invoke(plain, plain.size)
                    handling = false
                }
            }
        } catch (e: IOException) {
            end = if (handling) WifiLinkEnd.handlerFailed(e) else WifiLinkEnd.readFailed(e, timing.deadMs)
        } finally {
            die(end ?: WifiLinkEnd.loopEnded())
        }
    }

    /**
     * Close a socket whose write is stuck, a keepalive's included. It never writes itself: a write that blocks (the peer
     * stopped reading) must not stop the one check that ends it (the reviewer's 24 Nov finding 4).
     */
    private fun watch() {
        while (running.get()) {
            try {
                Thread.sleep(timing.tickMs)
            } catch (_: InterruptedException) {
                return
            }
            val since = writeSinceNs
            if (since != 0L && System.nanoTime() - since > timing.writeStallMs * NANOS_PER_MS) {
                log.w("wifi: a write didn't drain in ${timing.writeStallMs} ms — closing the link")
                die(WifiLinkEnd.writeStalled(timing.writeStallMs))
                return
            }
        }
    }

    /**
     * An empty record whenever nothing went out for [Timing.keepaliveMs], unless a write holds the line (traffic already).
     * On a thread of its own, which may block in its write: the watchdog then ends it.
     */
    private fun keepAlive() {
        while (running.get()) {
            try {
                Thread.sleep(timing.tickMs)
            } catch (_: InterruptedException) {
                return
            }
            if (System.nanoTime() - lastWriteNs < timing.keepaliveMs * NANOS_PER_MS || !writeLock.tryLock()) continue
            try {
                if (!running.get()) return
                writeSinceNs = System.nanoTime()
                output.write(session.tx.keepalive())
                output.flush()
                keepalivesSent++
                lastWriteNs = System.nanoTime()
            } catch (e: IOException) {
                die(WifiLinkEnd.keepaliveFailed(e))
                return
            } finally {
                writeSinceNs = 0L
                writeLock.unlock()
            }
        }
    }

    /** The link ended by itself: say how ([howItEnded]), close the socket, and tell the dead handler, once. */
    private fun die(end: WifiLinkEnd) {
        if (!running.getAndSet(false)) return
        val now = System.nanoTime()
        val wrote = bytesWritten > 0 || keepalivesSent > 0
        val stats =
            WifiLinkEnd.Stats(
                linkedMs = (now - startedNs) / NANOS_PER_MS,
                recordsIn = records,
                keepalivesIn = keepalivesReceived,
                bytesIn = bytesRead,
                lastInMsAgo = lastReadNs.takeIf { it != 0L }?.let { (now - it) / NANOS_PER_MS },
                bytesOut = bytesWritten,
                keepalivesOut = keepalivesSent,
                lastOutMsAgo = if (wrote) (now - lastWriteNs) / NANOS_PER_MS else null,
            )
        // A write that failed earlier doesn't end the link by itself (the read side usually says why soon after): say it too.
        val line = end.line(stats) + (lastError?.let { "; a write had failed before: $it" } ?: "")
        endLine = line
        lastError = lastError ?: end.toString()
        log.i("wifi link to $peer ended: $line")
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
        listOf(readThread, watchdog, keepaliveThread).forEach { t ->
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
        keepaliveThread = null
        log.i("wifi transport to $peer closed ($records records in, $bytesWritten B out)")
    }

    override fun diagnostics(): String =
        "wifi link $peer: $records records ($keepalivesReceived keepalives) in, the last " +
            (lastReadNs.takeIf { it != 0L }?.let { "${(System.nanoTime() - it) / NANOS_PER_MS} ms ago" } ?: "never") +
            ", $bytesRead B in, $bytesWritten B out, $keepalivesSent keepalives out, last error ${lastError ?: "none"}"
}
