package com.carlink.ui.pane

import com.carlink.ui.pane.PaneCard.CLOCK
import com.carlink.ui.pane.PaneCard.DIRECTIONS
import com.carlink.ui.pane.PaneCard.MEDIA
import com.carlink.ui.pane.PaneCard.VEHICLE
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
    fun `the truck's card comes after the clock, so stepping aside still lands on the clock`() {
        assertEquals(listOf(MEDIA, CLOCK, VEHICLE), PaneDeck.pages(navigating = false, hasTrack = true, hasVehicle = true))
        assertEquals(listOf(CLOCK, VEHICLE), PaneDeck.pages(navigating = false, hasTrack = false, hasVehicle = true))
        assertEquals(CLOCK, PaneDeck.beside(MEDIA, listOf(MEDIA, CLOCK, VEHICLE)))
        assertEquals(CLOCK, PaneDeck.default(listOf(CLOCK, VEHICLE)))
        assertEquals("the driver swiped to the truck", VEHICLE, PaneDeck.onMusicStart(listOf(MEDIA, CLOCK, VEHICLE), shown = VEHICLE))
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
    fun `leaving the app the title put in front brings its card back`() {
        val nowPlaying = Aside(app = MEDIA, to = CLOCK)
        assertEquals(MEDIA, PaneDeck.onLeft(nowPlaying, shown = CLOCK, pages = listOf(MEDIA, CLOCK)))
        assertEquals(MEDIA, PaneDeck.onLeft(Aside(app = MEDIA, to = DIRECTIONS), shown = DIRECTIONS, pages = listOf(DIRECTIONS, MEDIA, CLOCK)))
        assertEquals("the driver swiped since", DIRECTIONS, PaneDeck.onLeft(nowPlaying, shown = DIRECTIONS, pages = listOf(DIRECTIONS, MEDIA, CLOCK)))
        assertEquals("the track went away", CLOCK, PaneDeck.onLeft(nowPlaying, shown = CLOCK, pages = listOf(CLOCK)))
    }

    @Test
    fun `music that begins takes the pane`() {
        assertEquals(MEDIA, PaneDeck.after(previous = listOf(CLOCK), current = listOf(MEDIA, CLOCK), shown = CLOCK))
        assertEquals(MEDIA, PaneDeck.after(previous = listOf(DIRECTIONS, CLOCK), current = listOf(DIRECTIONS, MEDIA, CLOCK), shown = DIRECTIONS))
    }

    @Test
    fun `music that starts playing takes the pane from the clock only`() {
        assertEquals(MEDIA, PaneDeck.onMusicStart(pages = listOf(MEDIA, CLOCK), shown = CLOCK))
        assertEquals(MEDIA, PaneDeck.onMusicStart(pages = listOf(DIRECTIONS, MEDIA, CLOCK), shown = CLOCK))
        assertEquals("the driver is looking at the route", DIRECTIONS, PaneDeck.onMusicStart(pages = listOf(DIRECTIONS, MEDIA, CLOCK), shown = DIRECTIONS))
        assertEquals("no music card to show", CLOCK, PaneDeck.onMusicStart(pages = listOf(CLOCK), shown = CLOCK))
        assertEquals(MEDIA, PaneDeck.onMusicStart(pages = listOf(MEDIA, CLOCK), shown = MEDIA))
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
