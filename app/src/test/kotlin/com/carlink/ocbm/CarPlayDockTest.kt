package com.carlink.ocbm

import com.carlink.ocbm.seam.ViewAreaRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayDockTest {
    // The truck at 25 %: CarPlay 1800 x 960 on the left of a 2400 x 960 frame, the pane on the right.
    private val paneRight = ViewAreaRect(2400, 960, 0, 0, 1800, 960)

    // The pane on the left: CarPlay from x = 600.
    private val paneLeft = ViewAreaRect(2400, 960, 600, 0, 1800, 960)

    private fun at(px: Int) = px / 2400f

    @Test
    fun `the Dock is the driver's edge of CarPlay's area`() {
        assertTrue(CarPlayDock.contains(paneRight, onRight = false, x = at(100), y = 0.5f))
        assertFalse("past the Dock, on Now Playing's cover", CarPlayDock.contains(paneRight, onRight = false, x = at(300), y = 0.5f))
        assertTrue("right-hand drive", CarPlayDock.contains(paneRight, onRight = true, x = at(1700), y = 0.5f))
        assertFalse(CarPlayDock.contains(paneRight, onRight = true, x = at(100), y = 0.5f))
        assertTrue(CarPlayDock.contains(paneLeft, onRight = false, x = at(700), y = 0.9f))
    }

    @Test
    fun `the pane is never the Dock`() {
        assertFalse(CarPlayDock.contains(paneRight, onRight = true, x = at(1900), y = 0.5f))
        assertFalse(CarPlayDock.contains(paneLeft, onRight = false, x = at(500), y = 0.5f))
    }

    @Test
    fun `the Dock is a fifth of the area's height wide, measured in from its outer edge`() {
        assertEquals(192f, CarPlayDock.width(paneRight), 0.01f)
        assertEquals(150f, CarPlayDock.depth(paneLeft, onRight = false, x = at(750)), 0.01f)
        assertEquals(50f, CarPlayDock.depth(paneRight, onRight = true, x = at(1750)), 0.01f)
        assertTrue("on the pane", CarPlayDock.depth(paneLeft, onRight = false, x = at(500)) < 0f)
    }
}
