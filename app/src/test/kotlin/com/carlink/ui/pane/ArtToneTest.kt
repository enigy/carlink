package com.carlink.ui.pane

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtToneTest {
    private fun solid(argb: Int) = IntArray(256) { argb }

    @Test
    fun `black and white covers`() {
        assertTrue(ArtTone.isDark(solid(0xFF000000.toInt())))
        assertFalse(ArtTone.isDark(solid(0xFFFFFFFF.toInt())))
    }

    @Test
    fun `green weighs more than blue, as the eye sees it`() {
        assertFalse("pure green reads light", ArtTone.isDark(solid(0xFF00FF00.toInt())))
        assertTrue("pure blue reads dark", ArtTone.isDark(solid(0xFF0000FF.toInt())))
    }

    @Test
    fun `a half-and-half cover averages`() {
        val px = IntArray(256) { if (it < 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertTrue("mean 127.5 is just under the threshold", ArtTone.isDark(px))
    }

    @Test
    fun `no pixels means dark, the pane's own background`() {
        assertTrue(ArtTone.isDark(IntArray(0)))
    }
}
