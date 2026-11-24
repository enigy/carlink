package com.carlink.ocbm

import com.carlink.logging.ProbeLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** The Wi-Fi link's transport against a fake box on loopback: the stream both ways, the keepalive, and the dead-link rule. */
class WifiBulkTransportTest {
    private val key = ByteArray(32) { 0x42 }
    private val server = ServerSocket(0)
    private val fast = WifiBulkTransport.Timing(connectMs = 2_000, handshakeMs = 2_000, keepaliveMs = 100, deadMs = 400, writeStallMs = 400, tickMs = 20)
    private val open = ArrayList<WifiBulkTransport>()

    @After
    fun tearDown() {
        open.forEach { it.stop() }
        server.close()
    }

    @Test
    fun `the dead-link rule is a keepalive each second and three seconds of silence`() {
        val t = WifiBulkTransport.Timing()
        assertEquals(1_000L, t.keepaliveMs)
        assertEquals(3_000, t.deadMs)
        assertEquals(3_000L, t.writeStallMs)
        assertEquals(7020, OcbwLink.PORT)
    }

    @Test
    fun `a session carries the OCBM stream both ways, keepalives dropped`() {
        val fromHost = LinkedBlockingQueue<String>()
        fakeBox { s, session ->
            val din = DataInputStream(s.getInputStream())
            while (true) {
                val p = session.rx.read(din) ?: break
                if (p.isNotEmpty()) {
                    fromHost.add(String(p))
                    s.getOutputStream().write(session.tx.keepalive() + session.tx.seal("hello host".toByteArray()))
                }
            }
        }
        val got = LinkedBlockingQueue<String>()
        val t = link()
        t.setReadHandler { b, n -> got.add(String(b, 0, n)) }
        t.start()
        assertTrue(t.writeBulk("hello box".toByteArray()))
        assertEquals("hello box", fromHost.poll(5, TimeUnit.SECONDS))
        assertEquals("hello host", got.poll(5, TimeUnit.SECONDS))
        assertTrue(t.keepalivesReceived >= 1)
    }

    @Test
    fun `a write over one record's size goes out as several and arrives whole`() {
        val total = AtomicInteger()
        val done = CountDownLatch(1)
        val big = ByteArray(WifiBulkTransport.WRITE_CHUNK * 3 + 5) { (it % 251).toByte() }
        fakeBox { s, session ->
            val din = DataInputStream(s.getInputStream())
            while (total.get() < big.size) {
                val p = session.rx.read(din) ?: break
                assertTrue(p.size <= WifiBulkTransport.WRITE_CHUNK)
                total.addAndGet(p.size)
            }
            done.countDown()
        }
        val t = link()
        t.start()
        assertTrue(t.writeBulk(big))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(big.size, total.get())
    }

    @Test
    fun `an idle host sends a keepalive every keepalive period`() {
        val keepalives = AtomicInteger()
        fakeBox { s, session ->
            val din = DataInputStream(s.getInputStream())
            while (true) {
                val p = session.rx.read(din) ?: break
                if (p.isEmpty()) keepalives.incrementAndGet()
                s.getOutputStream().write(session.tx.keepalive()) // keeps the host's read side alive
            }
        }
        val t = link()
        t.start()
        waitUntil(3_000) { keepalives.get() >= 3 }
        assertTrue(t.keepalivesSent >= 3)
    }

    @Test
    fun `a box that goes silent is dead within the dead time, and only once`() {
        fakeBox { _, _ -> Thread.sleep(5_000) } // linked, then nothing at all
        val deaths = AtomicInteger()
        val dead = CountDownLatch(1)
        val t = link()
        t.setDeadHandler {
            deaths.incrementAndGet()
            dead.countDown()
        }
        val started = System.nanoTime()
        t.start()
        assertTrue(dead.await(3, TimeUnit.SECONDS))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("dead after $ms ms, the rule is ${fast.deadMs}", ms in (fast.deadMs - 50L)..(fast.deadMs + 600L))
        Thread.sleep(200)
        assertEquals(1, deaths.get())
        assertFalse(t.writeBulk(byteArrayOf(1)))
    }

    @Test
    fun `a box that sends only keepalives stays linked`() {
        fakeBox { s, session ->
            repeat(15) {
                s.getOutputStream().write(session.tx.keepalive())
                Thread.sleep(fast.keepaliveMs)
            }
        }
        val dead = CountDownLatch(1)
        val t = link()
        t.setDeadHandler { dead.countDown() }
        t.start()
        assertFalse("keepalives keep the link", dead.await(fast.deadMs * 3L, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a box that stops reading ends the link within the write-stall time, though it keeps sending`() {
        fakeBox { s, session ->
            // Never reads; its keepalives keep the host's read side alive, so only the stuck write can end the link.
            repeat(200) {
                s.getOutputStream().write(session.tx.keepalive())
                Thread.sleep(25)
            }
        }
        val dead = CountDownLatch(1)
        val t = link()
        t.setDeadHandler { dead.countDown() }
        t.start()
        val failed = CountDownLatch(1)
        val stuckAt = AtomicLong()
        thread(isDaemon = true) {
            val chunk = ByteArray(1 shl 20)
            stuckAt.set(System.nanoTime())
            while (t.writeBulk(chunk)) stuckAt.set(System.nanoTime())
            failed.countDown()
        }
        assertTrue("the stuck write ends the link", dead.await(5, TimeUnit.SECONDS))
        val ms = (System.nanoTime() - stuckAt.get()) / 1_000_000
        assertTrue("dead $ms ms after the last write that drained; the rule is ${fast.writeStallMs}", ms <= fast.writeStallMs * 3)
        assertTrue("the stuck write returns false", failed.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun `the box closing the link fires the dead handler`() {
        fakeBox { s, _ -> s.close() }
        val dead = CountDownLatch(1)
        val t = link()
        t.setDeadHandler { dead.countDown() }
        t.start()
        assertTrue(dead.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun `a box with another key, or one that closes at once, never links`() {
        thread(isDaemon = true) {
            server.accept().use { s -> runCatching { OcbwLink.boxHandshake(s.getInputStream(), s.getOutputStream(), ByteArray(32) { 9 }) } }
        }
        try {
            link()
            fail("a host with another key must not link")
        } catch (e: OcbwLink.LinkException) {
            assertTrue(e.keyRefused)
        }
        thread(isDaemon = true) { server.accept().close() }
        try {
            link()
            fail("a box that closes at once refuses the host")
        } catch (e: IOException) {
            assertFalse(e is OcbwLink.LinkException && e.keyRefused)
        }
    }

    // ---- how the link ended, and which side ended it (bench 24 Nov, 12:22: "link dead: read loop died", nothing more) ----

    @Test
    fun `the session's dead link says which side ended it, not only that the read loop died`() {
        fakeBox { s, session ->
            s.getOutputStream().write(session.tx.keepalive())
            Thread.sleep(150)
            s.shutdownOutput() // the far end closes its side: a TCP end of stream
            Thread.sleep(2_000)
        }
        val c = OcbmClient(link(), ProbeLog.silent())
        val reason = LinkedBlockingQueue<String>()
        c.onLinkDead = { reason.add(it) }
        c.start()
        val why = reason.poll(3, TimeUnit.SECONDS) ?: "no link death"
        c.stop(sendStop = false)
        assertTrue(why, why.startsWith("closed by the adapter's side: the TCP connection ended between records"))
        assertTrue(why, why.contains("in 1 records (1 keepalives)"))
    }

    @Test
    fun `a box that goes silent is closed by Carlink, under the dead-link rule`() {
        fakeBox { _, _ -> Thread.sleep(5_000) }
        val why = endOf(link())
        assertTrue(why, why.startsWith("closed by Carlink: no bytes from the adapter for ${fast.deadMs} ms (the dead-link rule); linked "))
        assertTrue(why, why.contains("; nothing in; "))
    }

    @Test
    fun `a box that stops reading is closed by Carlink, for the write that didn't drain`() {
        fakeBox { s, session ->
            repeat(200) {
                s.getOutputStream().write(session.tx.keepalive())
                Thread.sleep(25)
            }
        }
        val t = link()
        val written = AtomicInteger()
        val why =
            endOf(t) {
                thread(isDaemon = true) {
                    val chunk = ByteArray(1 shl 20)
                    while (t.writeBulk(chunk)) written.incrementAndGet()
                }
            }
        assertTrue(why, why.startsWith("closed by Carlink: a write to the adapter didn't drain in ${fast.writeStallMs} ms"))
    }

    @Test
    fun `a record that fails its check is refused by Carlink`() {
        fakeBox { s, session ->
            val r = session.tx.seal("hello host".toByteArray())
            r[r.size - 1] = (r[r.size - 1].toInt() xor 1).toByte()
            s.getOutputStream().write(r)
            Thread.sleep(2_000)
        }
        val why = endOf(link())
        assertTrue(why, why.startsWith("closed by Carlink: a record from the adapter was refused (a record failed authentication)"))
    }

    @Test
    fun `a box that resets the connection is the adapter's side`() {
        fakeBox { s, session ->
            s.getOutputStream().write(session.tx.keepalive())
            Thread.sleep(150)
            s.setSoLinger(true, 0)
            s.close() // a TCP reset, not an end of stream
        }
        val why = endOf(link())
        assertTrue(why, why.startsWith("closed by the adapter's side: ") && why.contains("found the connection reset or closed"))
    }

    @Test
    fun `Carlink's own handling of the data failing is Carlink's, not the adapter's end of stream`() {
        fakeBox { s, session ->
            s.getOutputStream().write(session.tx.seal("hello host".toByteArray()))
            Thread.sleep(2_000)
        }
        val t = link()
        t.setReadHandler { _, _ -> throw EOFException("a parser ran short") }
        val why = endOf(t)
        assertTrue(why, why.startsWith("closed by Carlink: its own handling of the adapter's data failed (EOFException: a parser ran short)"))
    }

    @Test
    fun `a link Carlink stops in order names no end`() {
        fakeBox { s, session ->
            repeat(40) {
                s.getOutputStream().write(session.tx.keepalive())
                Thread.sleep(fast.keepaliveMs)
            }
        }
        val t = link()
        t.start()
        waitUntil(2_000) { t.keepalivesReceived >= 1 }
        t.stop()
        assertNull(t.howItEnded)
    }

    /** Start [t], then [started], and wait for the link to die by itself: its end line. */
    private fun endOf(
        t: WifiBulkTransport,
        started: () -> Unit = {},
    ): String {
        val dead = CountDownLatch(1)
        t.setDeadHandler { dead.countDown() }
        t.start()
        started()
        assertTrue("the link ends by itself", dead.await(5, TimeUnit.SECONDS))
        return t.howItEnded ?: "no end reason"
    }

    private fun link(): WifiBulkTransport = WifiBulkTransport.connect(InetSocketAddress("127.0.0.1", server.localPort), key, ProbeLog.silent(), timing = fast).also { open.add(it) }

    /** One fake box: accepts, runs the box's handshake, then [body]. */
    private fun fakeBox(body: (Socket, OcbwLink.Session) -> Unit) {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val session = OcbwLink.boxHandshake(s.getInputStream(), s.getOutputStream(), key)
                    body(s, session)
                }
            }
        }
    }

    private fun waitUntil(
        ms: Long,
        cond: () -> Boolean,
    ) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!cond()) {
            if (System.nanoTime() > end) fail("not within $ms ms")
            Thread.sleep(10)
        }
    }
}
