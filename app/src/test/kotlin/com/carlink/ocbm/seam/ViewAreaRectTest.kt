package com.carlink.ocbm.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The view-area rect read out of the 128-byte AirPlayScreenHeader. */
class ViewAreaRectTest {
    /**
     * A header with the six floats where iOS puts them — coded width and height, then the area's x, y,
     * width and height — and everything else zero, after [prefix] bytes of something else.
     */
    private fun header(
        vararg f: Float,
        prefix: Int = 0,
    ): ByteArray {
        val b = ByteBuffer.allocate(prefix + SeamCrypto.HDR_LEN).order(ByteOrder.LITTLE_ENDIAN)
        val offsets = listOf(ViewAreaRect.CODED_WIDTH_OFF, ViewAreaRect.CODED_HEIGHT_OFF, ViewAreaRect.X_OFF, ViewAreaRect.Y_OFF, ViewAreaRect.WIDTH_OFF, ViewAreaRect.HEIGHT_OFF)
        offsets.forEachIndexed { i, off -> b.putFloat(prefix + off, f[i]) }
        return b.array()
    }

    @Test
    fun `the split area of the proven run parses, at an offset into a larger message`() {
        val r = ViewAreaRect.parse(header(2400f, 960f, 800f, 0f, 1600f, 960f, prefix = 9), off = 9)
        assertEquals(ViewAreaRect(2400, 960, 800, 0, 1600, 960), r)
        assertFalse(r!!.isFull)
        assertTrue(r.paneOnLeft)
        assertEquals(1f / 3f, r.paneFraction, 0.0001f)
    }

    @Test
    fun `the full panel is full, with no pane`() {
        val r = ViewAreaRect.parse(header(2400f, 960f, 0f, 0f, 2400f, 960f))!!
        assertTrue(r.isFull)
        assertEquals(0f, r.paneFraction, 0f)
        assertFalse(r.paneOnLeft)
    }

    @Test
    fun `a pane on the right`() {
        val r = ViewAreaRect.parse(header(2400f, 960f, 0f, 0f, 1680f, 960f))!!
        assertFalse(r.paneOnLeft)
        assertEquals(0.3f, r.paneFraction, 0.0001f)
    }

    @Test
    fun `an animation step with a fractional width rounds to the nearest pixel`() {
        assertEquals(1703, ViewAreaRect.parse(header(2400f, 960f, 0f, 0f, 1702.6f, 960f))!!.width)
    }

    @Test
    fun `garbage is not a rect`() {
        assertNull("short buffer", ViewAreaRect.parse(ByteArray(0x2f)))
        assertNull("all zero", ViewAreaRect.parse(ByteArray(SeamCrypto.HDR_LEN)))
        assertNull("NaN", ViewAreaRect.parse(header(Float.NaN, 960f, 0f, 0f, 1680f, 960f)))
        assertNull("negative origin", ViewAreaRect.parse(header(2400f, 960f, -8f, 0f, 1680f, 960f)))
        assertNull("absurd size", ViewAreaRect.parse(header(2400f, 960f, 0f, 0f, 1e9f, 960f)))
        assertNull("offset past the end", ViewAreaRect.parse(header(2400f, 960f, 0f, 0f, 1680f, 960f), off = 100))
    }
}
