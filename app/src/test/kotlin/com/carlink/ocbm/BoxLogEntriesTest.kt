package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class BoxLogEntriesTest {
    private fun entry(
        source: Int,
        flags: Int,
        seq: Int,
        unixMs: Long,
        body: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(source)
        out.write(flags)
        out.write(seq and 0xFF)
        out.write((seq shr 8) and 0xFF)
        for (k in 0 until 8) out.write(((unixMs shr (8 * k)) and 0xFF).toInt())
        out.write(body.size and 0xFF)
        out.write((body.size shr 8) and 0xFF)
        out.write(body)
        return out.toByteArray()
    }

    @Test
    fun `two entries in one frame decode in order with their source names`() {
        val frame =
            entry(Ocbm.LOG_SRC_IAP2D, 0, 0x0102, 1_760_000_000_123L, "route guidance update\n".toByteArray()) +
                entry(Ocbm.LOG_SRC_BOX, 0, 7, 1_760_000_000_456L, "usb host up".toByteArray())
        val got = BoxLogEntries.parse(frame)
        assertEquals(2, got.size)
        assertEquals("iap2d", got[0].sourceName)
        assertEquals(0x0102, got[0].seq)
        assertEquals(1_760_000_000_123L, got[0].unixMs)
        assertEquals("route guidance update", got[0].text)
        assertEquals("box", got[1].sourceName)
        assertEquals("usb host up", got[1].text)
    }

    @Test
    fun `a drop report becomes a visible gap line`() {
        val count = byteArrayOf(42, 1, 0, 0) // 298 LE
        val got = BoxLogEntries.parse(entry(Ocbm.LOG_SRC_INTERNAL, Ocbm.LOG_F_DROPPED, 3, 0L, count))
        assertEquals(1, got.size)
        assertTrue(got[0].text, got[0].text.contains("298"))
    }

    @Test
    fun `a torn tail keeps the entries before it`() {
        val good = entry(Ocbm.LOG_SRC_BT, 0, 1, 5L, "ok".toByteArray())
        val torn = entry(Ocbm.LOG_SRC_BT, 0, 2, 6L, "cut off here".toByteArray()).copyOf(Ocbm.LOG_ENTRY_HDR + 3)
        val got = BoxLogEntries.parse(good + torn)
        assertEquals(listOf("ok"), got.map { it.text })
    }

    @Test
    fun `an unknown source is labelled not dropped`() {
        val got = BoxLogEntries.parse(entry(0x7E, 0, 1, 1L, "x".toByteArray()))
        assertEquals("src126", got.single().sourceName)
    }
}
