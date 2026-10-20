package com.carlink.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollThumbTest {
    @Test
    fun `thumb is the visible share and travels the rest of the track`() {
        // 600 px track, 800 px of 1200 visible: thumb is two thirds, 400 px.
        assertEquals(ThumbGeometry(0, 400), ScrollThumb.geometry(track = 600, value = 0, max = 400, viewport = 800, minThumb = 64))
        assertEquals(ThumbGeometry(100, 400), ScrollThumb.geometry(track = 600, value = 200, max = 400, viewport = 800, minThumb = 64))
        assertEquals(ThumbGeometry(200, 400), ScrollThumb.geometry(track = 600, value = 400, max = 400, viewport = 800, minThumb = 64))
    }

    @Test
    fun `a very long page keeps a thumb you can see`() {
        val g = ScrollThumb.geometry(track = 600, value = 0, max = 50_000, viewport = 800, minThumb = 64)
        assertEquals(64, g.height)
    }

    @Test
    fun `nothing to scroll or no track yet fills the track`() {
        assertEquals(ThumbGeometry(0, 600), ScrollThumb.geometry(track = 600, value = 0, max = 0, viewport = 800, minThumb = 64))
        assertEquals(ThumbGeometry(0, 0), ScrollThumb.geometry(track = 0, value = 0, max = 400, viewport = 800, minThumb = 64))
    }

    @Test
    fun `a page step keeps some of the last screen in view`() {
        assertEquals(640f, ScrollThumb.pageStep(800))
    }
}
