package com.carlink.ocbm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/** OCBW v1 against the box's crate (enigy/ocbm `crates/ocbm-link`): its known-answer vectors byte for byte, records, handshakes. */
class OcbwLinkTest {
    private val key = ByteArray(32) { 0x11 }
    private val bn = ByteArray(32) { 0x22 }
    private val hn = ByteArray(32) { 0x33 }

    @Test
    fun `the crate's known-answer vector, byte for byte`() {
        assertEquals("37256608df8b1738ce01a824112515ab0f72420ad890eb5f28b8e1378ef3c276", OcbwLink.hostProof(key, bn, hn).toHex())
        assertEquals("6b0464993905c9cb244c394e2114ed5db546a3e01a882fa33e18e9b4a88b5ea0", OcbwLink.boxProof(key, hn, bn).toHex())
        val keys = OcbwLink.sessionKeys(key, bn, hn)
        assertEquals("cdbc30ed5bb1352687e3175ad53c08496750fdf4cbce95eeee76e9f66c4fa29a", keys.hostToBox.toHex())
        assertEquals("000000140cfc70b3b120a8adfad9270f9691f573c1d1ad5d", OcbwLink.Sealer(keys.hostToBox).seal("OCBM".toByteArray()).toHex())
        assertEquals("cbb6a0d2a8710450", OcbwLink.boxId(key))
    }

    @Test
    fun `the adapter side's added vectors, the keepalive at counter 1 and the box-to-host direction`() {
        val keys = OcbwLink.sessionKeys(key, bn, hn)
        val h2b = OcbwLink.Sealer(keys.hostToBox)
        h2b.seal("OCBM".toByteArray())
        assertEquals("000000101d776e024c61ad6502aacb8d5ec4212d", h2b.keepalive().toHex())
        assertEquals("d723f96563fcb333c34f86768c5fd20e3d1abfde199e74fd77865de21deb562e", keys.boxToHost.toHex())
        assertEquals("000000147917df2d4400fb351fce0de900778d87878bd4ec", OcbwLink.Sealer(keys.boxToHost).seal("OCBM".toByteArray()).toHex())
    }

    @Test
    fun `records open split anywhere, a keepalive opens empty, and a replay, a tamper or a reorder ends the link`() {
        val k = OcbwLink.sessionKeys(key, bn, hn).hostToBox
        val tx = OcbwLink.Sealer(k)
        val r0 = tx.seal("zero".toByteArray())
        val ka = tx.keepalive()
        val r1 = tx.seal("one".toByteArray())
        val rx = OcbwLink.Opener(k)
        rx.push(r0.copyOf(3))
        assertNull(rx.next())
        rx.push(r0.copyOfRange(3, r0.size) + ka + r1)
        assertEquals("zero", String(rx.next()!!))
        assertEquals(0, rx.next()!!.size)
        assertEquals("one", String(rx.next()!!))
        rx.push(r1)
        assertRefused { rx.next() }

        val reorder = OcbwLink.Opener(k)
        reorder.push(r1)
        assertRefused { reorder.next() }

        val tampered = OcbwLink.Sealer(k).seal("data".toByteArray()).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val rx2 = OcbwLink.Opener(k)
        rx2.push(tampered)
        assertRefused { rx2.next() }
    }

    @Test
    fun `a length outside 16 to 64 KiB plus the tag is refused before anything is buffered`() {
        val rx = OcbwLink.Opener(ByteArray(32))
        rx.push(byteArrayOf(0, 1, 0, 0x11)) // 65553
        assertRefused { rx.next() }
        val short = OcbwLink.Opener(ByteArray(32))
        short.push(byteArrayOf(0, 0, 0, 15))
        assertRefused { short.next() }
        assertRefused { OcbwLink.Opener(ByteArray(32)).read(DataInputStream(ByteArrayInputStream(byteArrayOf(0, 2, 0, 0)))) }
    }

    @Test
    fun `read gives one record at a time, and null at a clean end between records`() {
        val k = ByteArray(32) { 5 }
        val tx = OcbwLink.Sealer(k)
        val wire = tx.seal("a".toByteArray()) + tx.seal(ByteArray(OcbwLink.MAX_RECORD) { 7 })
        val input = DataInputStream(ByteArrayInputStream(wire))
        val rx = OcbwLink.Opener(k)
        assertEquals("a", String(rx.read(input)!!))
        assertEquals(OcbwLink.MAX_RECORD, rx.read(input)!!.size)
        assertNull(rx.read(input))
    }

    @Test
    fun `a host and a box with one key agree on both directions' keys`() {
        val (host, box) = handshake(key, key)
        val toBox = host!!.tx.seal("hello box".toByteArray())
        assertEquals("hello box", String(box!!.rx.read(DataInputStream(ByteArrayInputStream(toBox)))!!))
        val toHost = box.tx.seal("hello host".toByteArray())
        assertEquals("hello host", String(host.rx.read(DataInputStream(ByteArrayInputStream(toHost)))!!))
    }

    @Test
    fun `a host with another key is refused by the box, and a box with another key by the host`() {
        val (_, box) = handshake(hostKey = ByteArray(32) { 8 }, boxKey = key)
        assertNull("the box refuses a wrong proof", box)
        try {
            fakeBoxWithWrongProof()
            fail("a box that proves another key must not link")
        } catch (e: OcbwLink.LinkException) {
            assertTrue(e.keyRefused)
        }
    }

    @Test
    fun `hex both ways`() {
        assertEquals("00ff10", byteArrayOf(0, -1, 16).toHex())
        assertArrayEquals(byteArrayOf(0, -1, 16), hexBytes("00FF10"))
        assertNull(hexBytes("abc"))
        assertNull(hexBytes("zz"))
    }

    /** A host and a box over two pipes; each side's session, or null when it refused. */
    private fun handshake(
        hostKey: ByteArray,
        boxKey: ByteArray,
    ): Pair<OcbwLink.Session?, OcbwLink.Session?> {
        val toBox = PipedOutputStream()
        val boxIn = PipedInputStream(toBox, 1 shl 16)
        val toHost = PipedOutputStream()
        val hostIn = PipedInputStream(toHost, 1 shl 16)
        var box: OcbwLink.Session? = null
        val t =
            thread {
                box = runCatching { OcbwLink.boxHandshake(boxIn, toHost, boxKey, bn) }.getOrNull()
                if (box == null) toHost.close() // a box refuses by closing
            }
        val host = runCatching { OcbwLink.hostHandshake(hostIn, toBox, hostKey, hn) }.getOrNull()
        t.join(5_000)
        return host to box
    }

    private fun fakeBoxWithWrongProof() {
        val hello = OcbwLink.MAGIC + byteArrayOf(OcbwLink.VERSION) + bn
        val wrongProof = ByteArray(32) { 1 }
        val sink = java.io.ByteArrayOutputStream()
        OcbwLink.hostHandshake(ByteArrayInputStream(hello + wrongProof), sink, key, hn)
    }

    private fun assertRefused(block: () -> Unit) {
        try {
            block()
            fail("expected a LinkException")
        } catch (_: OcbwLink.LinkException) {
        }
    }
}
