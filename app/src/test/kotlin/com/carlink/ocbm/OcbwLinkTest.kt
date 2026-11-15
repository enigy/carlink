package com.carlink.ocbm

import com.carlink.logging.ProbeLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** OCBW v1 against the box's Rust crate (enigy/ocbm `crates/ocbm-link`, branch `wifi-transport`), byte for byte. */
@RunWith(RobolectricTestRunner::class)
class OcbwLinkTest {
    private val key = ByteArray(32) { 0x11 }
    private val bn = ByteArray(32) { 0x22 }
    private val hn = ByteArray(32) { 0x33 }

    @Test
    fun `the Rust crate's known-answer vector`() {
        assertEquals("37256608df8b1738ce01a824112515ab0f72420ad890eb5f28b8e1378ef3c276", OcbwLink.hostProof(key, bn, hn).toHex())
        assertEquals("6b0464993905c9cb244c394e2114ed5db546a3e01a882fa33e18e9b4a88b5ea0", OcbwLink.boxProof(key, hn, bn).toHex())
        val (h2b, _) = OcbwLink.sessionKeys(key, bn, hn)
        assertEquals("cdbc30ed5bb1352687e3175ad53c08496750fdf4cbce95eeee76e9f66c4fa29a", h2b.toHex())
        assertEquals("000000140cfc70b3b120a8adfad9270f9691f573c1d1ad5d", OcbwLink.Sealer(h2b).seal("OCBM".toByteArray()).toHex())
    }

    @Test
    fun `the box id is the Rust crate's`() {
        assertEquals("cbb6a0d2a8710450", OcbwLink.boxId(key))
    }

    @Test
    fun `WPA PSK matches IEEE 802_11i's test vector`() {
        assertEquals("f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e", OcbwLink.wpaPsk("password", "IEEE"))
    }

    @Test
    fun `records open split anywhere, and a replay or tamper ends the link`() {
        val (h2b, _) = OcbwLink.sessionKeys(key, bn, hn)
        val tx = OcbwLink.Sealer(h2b)
        val rx = OcbwLink.Opener(h2b)
        val r0 = tx.seal("zero".toByteArray())
        val r1 = tx.seal("one".toByteArray())
        rx.push(r0.copyOf(3))
        assertNull(rx.next())
        rx.push(r0.copyOfRange(3, r0.size) + r1)
        assertEquals("zero", String(rx.next()!!))
        assertEquals("one", String(rx.next()!!))
        rx.push(r1) // replay
        assertThrows { rx.next() }
        val rx2 = OcbwLink.Opener(h2b)
        val bad = OcbwLink.Sealer(h2b).seal("data".toByteArray()).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        rx2.push(bad)
        assertThrows { rx2.next() }
    }

    @Test
    fun `provisioning body is the layout ocbmd parses`() {
        val body = OcbwLink.provisionBody(key, "MyCar", "password")
        assertEquals(1, body[0].toInt())
        assertArrayEquals(key, body.copyOfRange(1, 33))
        assertEquals(5, body[33].toInt())
        assertEquals("MyCar", String(body, 34, 5))
        assertEquals(64, body[39].toInt())
        assertEquals(OcbwLink.wpaPsk("password", "MyCar"), String(body, 40, 64))
        assertEquals(104, body.size)
        // An empty SSID: link key only, no psk.
        assertEquals(35, OcbwLink.provisionBody(key, "", "").size)
        assertNotNull(OcbwLink.provisionProblem("MyCar", "short"))
        assertNotNull(OcbwLink.provisionProblem("x".repeat(33), "password"))
    }

    @Test
    fun `a session over loopback carries the stream both ways, and a wrong key is refused`() {
        ServerSocket(0).use { server ->
            val fromHost = LinkedBlockingQueue<String>()
            val boxDone = CountDownLatch(1)
            thread(isDaemon = true) {
                server.accept().use { s ->
                    val sess = OcbwLink.boxHandshake(s.getInputStream(), s.getOutputStream(), key)
                    val din = DataInputStream(s.getInputStream())
                    fromHost.add(String(sess.rx.read(din)!!))
                    s.getOutputStream().write(sess.tx.seal("hello host".toByteArray()))
                    s.getOutputStream().flush()
                    boxDone.await(5, TimeUnit.SECONDS)
                }
            }
            val got = LinkedBlockingQueue<String>()
            val t = WifiBulkTransport.connect("127.0.0.1", server.localPort, key, ProbeLog.silent())
            t.setReadHandler { b, n -> got.add(String(b, 0, n)) }
            val dead = CountDownLatch(1)
            t.setDeadHandler { dead.countDown() }
            t.start()
            assertTrue(t.writeBulk("hello box".toByteArray()))
            assertEquals("hello box", fromHost.poll(5, TimeUnit.SECONDS))
            assertEquals("hello host", got.poll(5, TimeUnit.SECONDS))
            boxDone.countDown()
            assertTrue("the box closing ends the link", dead.await(5, TimeUnit.SECONDS))
            t.stop()
        }
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                server.accept().use { s -> runCatching { OcbwLink.boxHandshake(s.getInputStream(), s.getOutputStream(), key) } }
            }
            try {
                WifiBulkTransport.connect("127.0.0.1", server.localPort, ByteArray(32) { 8 }, ProbeLog.silent())
                fail("a host with another key must not link")
            } catch (_: java.io.IOException) {
            }
        }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected a LinkException")
        } catch (_: OcbwLink.LinkException) {
        }
    }
}
