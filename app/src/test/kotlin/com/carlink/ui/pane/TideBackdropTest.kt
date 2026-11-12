package com.carlink.ui.pane

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drift + Tide's sums and colours (owner, 12 Nov, the pane backgrounds canvas). */
class TideBackdropTest {
    @Test
    fun `the fuel pane's water stands at the tank's level, the clock's is a low swell`() {
        assertEquals(0.38f, TideMath.waterline(fuel = true, level = 0.62f), 1e-6f)
        assertEquals("full", 0f, TideMath.waterline(fuel = true, level = 1.2f), 1e-6f)
        assertEquals("empty", 1f, TideMath.waterline(fuel = true, level = 0f), 1e-6f)
        assertTrue("unknown: no water, the line below the pane", TideMath.waterline(fuel = true, level = null) > 1f)
        assertEquals(TideMath.SWELL, TideMath.waterline(fuel = false, level = 0.1f), 1e-6f)
    }

    @Test
    fun `a field drifts out and back, eased, and a wave slides one pane width a period`() {
        assertEquals(0f, TideMath.pingPong(0, 40_000), 1e-6f)
        assertEquals("half way out", 0.5f, TideMath.pingPong(20_000, 40_000), 1e-6f)
        assertEquals("at its far point", 1f, TideMath.pingPong(40_000, 40_000), 1e-6f)
        assertEquals("back again", TideMath.pingPong(10_000, 40_000), TideMath.pingPong(70_000, 40_000), 1e-6f)
        assertEquals("reversed starts far", 1f, TideMath.pingPong(0, 40_000, reverse = true), 1e-6f)
        assertEquals(0f, TideMath.shift(0, 18_000, reverse = false), 1e-6f)
        assertEquals(-0.5f, TideMath.shift(9_000, 18_000, reverse = false), 1e-6f)
        assertEquals("reversed slides the other way", -1f, TideMath.shift(0, 26_000, reverse = true), 1e-6f)
    }

    @Test
    fun `day water is light, night water dark, and low fuel turns it amber`() {
        val day = TidePalette.of(night = false, low = false).water[0]
        val night = TidePalette.of(night = true, low = false).water[0]
        val amber = TidePalette.of(night = false, low = true).water[0]
        assertTrue("light by day", day.luminance() > 0.4f)
        assertTrue("dark by night", night.luminance() < 0.2f)
        assertTrue("amber: more red than blue", amber.red > amber.blue && day.blue > day.red)
    }
}
