package com.carlink.ui.pane

import com.carlink.ui.pane.PaneCard.CLOCK
import com.carlink.ui.pane.PaneCard.DIRECTIONS
import com.carlink.ui.pane.PaneCard.MEDIA
import org.junit.Assert.assertEquals
import org.junit.Test

class PaneDeckTest {
    @Test
    fun `the clock is always dealt, the route ahead of the music`() {
        assertEquals(listOf(CLOCK), PaneDeck.pages(navigating = false, hasTrack = false))
        assertEquals(listOf(MEDIA, CLOCK), PaneDeck.pages(navigating = false, hasTrack = true))
        assertEquals(listOf(DIRECTIONS, MEDIA, CLOCK), PaneDeck.pages(navigating = true, hasTrack = true))
    }

    @Test
    fun `a fresh pane shows the music before the route`() {
        assertEquals(MEDIA, PaneDeck.default(listOf(DIRECTIONS, MEDIA, CLOCK)))
        assertEquals(DIRECTIONS, PaneDeck.default(listOf(DIRECTIONS, CLOCK)))
        assertEquals(CLOCK, PaneDeck.default(listOf(CLOCK)))
    }

    @Test
    fun `beside CarPlay's app the pane shows the other card`() {
        // The source tap put Now Playing in front: the route, or the clock without one.
        assertEquals(DIRECTIONS, PaneDeck.beside(MEDIA, listOf(DIRECTIONS, MEDIA, CLOCK)))
        assertEquals(CLOCK, PaneDeck.beside(MEDIA, listOf(MEDIA, CLOCK)))
        // The Directions title put Maps in front: the music.
        assertEquals(MEDIA, PaneDeck.beside(DIRECTIONS, listOf(DIRECTIONS, MEDIA, CLOCK)))
        assertEquals(CLOCK, PaneDeck.beside(DIRECTIONS, listOf(DIRECTIONS, CLOCK)))
    }

    @Test
    fun `music that begins takes the pane`() {
        assertEquals(MEDIA, PaneDeck.after(previous = listOf(CLOCK), current = listOf(MEDIA, CLOCK), shown = CLOCK))
        assertEquals(MEDIA, PaneDeck.after(previous = listOf(DIRECTIONS, CLOCK), current = listOf(DIRECTIONS, MEDIA, CLOCK), shown = DIRECTIONS))
    }

    @Test
    fun `a route that begins takes the pane only from the clock`() {
        assertEquals(DIRECTIONS, PaneDeck.after(previous = listOf(CLOCK), current = listOf(DIRECTIONS, CLOCK), shown = CLOCK))
        assertEquals("the driver is looking at the music", MEDIA, PaneDeck.after(previous = listOf(MEDIA, CLOCK), current = listOf(DIRECTIONS, MEDIA, CLOCK), shown = MEDIA))
    }

    @Test
    fun `a card that goes away gives way to the default`() {
        assertEquals(MEDIA, PaneDeck.after(previous = listOf(DIRECTIONS, MEDIA, CLOCK), current = listOf(MEDIA, CLOCK), shown = DIRECTIONS))
        assertEquals(DIRECTIONS, PaneDeck.after(previous = listOf(DIRECTIONS, MEDIA, CLOCK), current = listOf(DIRECTIONS, CLOCK), shown = MEDIA))
        assertEquals(CLOCK, PaneDeck.after(previous = listOf(MEDIA, CLOCK), current = listOf(CLOCK), shown = MEDIA))
    }

    @Test
    fun `a swipe survives a reshuffle that keeps its card`() {
        assertEquals(CLOCK, PaneDeck.after(previous = listOf(DIRECTIONS, MEDIA, CLOCK), current = listOf(MEDIA, CLOCK), shown = CLOCK))
        assertEquals(DIRECTIONS, PaneDeck.after(previous = listOf(DIRECTIONS, MEDIA, CLOCK), current = listOf(DIRECTIONS, CLOCK), shown = DIRECTIONS))
    }
}
