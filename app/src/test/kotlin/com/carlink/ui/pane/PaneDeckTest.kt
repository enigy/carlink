package com.carlink.ui.pane

import com.carlink.ui.pane.PaneCard.CALL
import com.carlink.ui.pane.PaneCard.CLOCK
import com.carlink.ui.pane.PaneCard.DIRECTIONS
import com.carlink.ui.pane.PaneCard.FUEL
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
    fun `the clock has Drift + Tide, music playing or not, and only the music card the cover`() {
        assertEquals("no cover behind the clock (owner, 12 Nov)", PaneBackdrop.TIDE, PaneBackdrop.of(CLOCK))
        assertEquals(PaneBackdrop.BAND, PaneBackdrop.of(MEDIA))
        assertEquals(PaneBackdrop.PLAIN, PaneBackdrop.of(DIRECTIONS))
        assertEquals("a call", PaneBackdrop.PLAIN, PaneBackdrop.of(null))
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

    /** Owner, 12 Nov: during a call the driver can swipe to the Fuel card or the clock, and the music waits for its end. */
    @Test
    fun `a call leads the deck and keeps the music out until it ends`() {
        assertEquals(listOf(CALL, DIRECTIONS, FUEL, CLOCK), PaneDeck.pages(navigating = true, hasTrack = true, fuel = true, calling = true))
        assertEquals(listOf(CALL, CLOCK), PaneDeck.pages(navigating = false, hasTrack = true, calling = true))
        assertEquals(PaneBackdrop.PLAIN, PaneBackdrop.of(CALL))
    }

    @Test
    fun `a call takes the pane, and its end gives it back to the card it found`() {
        val before = listOf(FUEL, MEDIA, CLOCK)
        val during = listOf(CALL, FUEL, CLOCK)
        assertEquals(CALL, PaneDeck.after(before, during, shown = MEDIA))
        assertEquals("the music it found comes back", MEDIA, PaneDeck.after(during, before, shown = CALL, beforeCall = MEDIA))
        assertEquals("the clock it found", CLOCK, PaneDeck.after(during, before, shown = CALL, beforeCall = CLOCK))
        assertEquals("a card swiped to during the call stays, the music taking nothing", FUEL, PaneDeck.after(during, before, shown = FUEL, beforeCall = MEDIA))
        assertEquals("not known: the default", MEDIA, PaneDeck.after(during, before, shown = CALL))
        assertEquals("a call that began on a card since gone", CLOCK, PaneDeck.after(listOf(CALL, DIRECTIONS, CLOCK), listOf(CLOCK), shown = CALL, beforeCall = DIRECTIONS))
    }

    @Test
    fun `low fuel doesn't pull the pane off a call`() {
        assertEquals(CALL, LowFuelDeck.onLow(listOf(CALL, FUEL, CLOCK), CALL))
        assertEquals(FUEL, LowFuelDeck.onLow(listOf(CALL, FUEL, CLOCK), CLOCK))
    }
}
