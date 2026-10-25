package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The CarPlay area of the split layout, against the rules iOS tears a session down for. */
class SplitViewAreaTest {
    @Test
    fun `the truck panel at 30 percent is the device-proven shape`() {
        assertEquals(PixelRect(0, 0, 1680, 960), SplitViewArea.carPlayArea(2400, 960, paneOnLeft = false, panePercent = 30))
        assertEquals(PixelRect(720, 0, 1680, 960), SplitViewArea.carPlayArea(2400, 960, paneOnLeft = true, panePercent = 30))
    }

    @Test
    fun `the other two widths`() {
        assertEquals(PixelRect(0, 0, 1800, 960), SplitViewArea.carPlayArea(2400, 960, paneOnLeft = false, panePercent = 25))
        assertEquals(PixelRect(840, 0, 1560, 960), SplitViewArea.carPlayArea(2400, 960, paneOnLeft = true, panePercent = 35))
    }

    @Test
    fun `a visible system bar gives a shorter area, still even`() {
        // 2400x788 content area (status bar visible): the height follows the declared panel.
        assertEquals(PixelRect(0, 0, 1680, 788), SplitViewArea.carPlayArea(2400, 788, paneOnLeft = false, panePercent = 30))
    }

    @Test
    fun `an odd width rounds down to even and the left origin stays even`() {
        // 1366 * 0.65 = 887.9 -> 886; 1366 - 886 = 480.
        assertEquals(PixelRect(480, 0, 886, 768), SplitViewArea.carPlayArea(1366, 768, paneOnLeft = true, panePercent = 35))
    }

    @Test
    fun `an area below the CarPlay floor is not declared`() {
        // 1000 * 0.65 = 650 < 800: iOS would lock the area out black, so the session stays full screen.
        assertNull(SplitViewArea.carPlayArea(1000, 600, paneOnLeft = false, panePercent = 35))
    }

    @Test
    fun `a nonsense percentage is refused`() {
        assertNull(SplitViewArea.carPlayArea(2400, 960, paneOnLeft = false, panePercent = 0))
        assertNull(SplitViewArea.carPlayArea(2400, 960, paneOnLeft = false, panePercent = 100))
    }
}
