package com.carlink.ui.settings

import com.carlink.ocbm.PixelRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Settings › Display › Layout, reduced to the view area it declares. */
class LayoutConfigTest {
    @Test
    fun `the default is full screen and declares no second area`() {
        val c = LayoutConfig()
        assertFalse(c.split)
        assertNull(c.carPlayArea(2400, 960))
    }

    @Test
    fun `media on the right puts CarPlay on the left at 30 percent`() {
        assertEquals(PixelRect(0, 0, 1680, 960), LayoutConfig(PaneLayout.MEDIA_RIGHT).carPlayArea(2400, 960))
    }

    @Test
    fun `media on the left at 25 percent`() {
        assertEquals(PixelRect(600, 0, 1800, 960), LayoutConfig(PaneLayout.MEDIA_LEFT, PaneWidth.NARROW).carPlayArea(2400, 960))
    }

    @Test
    fun `an unknown or missing stored code falls back to the defaults`() {
        assertEquals(PaneLayout.FULL, PaneLayout.fromCode(null))
        assertEquals(PaneLayout.FULL, PaneLayout.fromCode("diagonal"))
        assertEquals(PaneLayout.MEDIA_LEFT, PaneLayout.fromCode("left"))
        assertEquals(PaneWidth.STANDARD, PaneWidth.fromCode(null))
        assertEquals(PaneWidth.WIDE, PaneWidth.fromCode("35"))
    }
}
