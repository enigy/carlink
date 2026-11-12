package com.carlink.ui.pane

import com.carlink.ui.pane.PaneCard.CLOCK
import com.carlink.ui.pane.PaneCard.DIRECTIONS
import com.carlink.ui.pane.PaneCard.FUEL
import com.carlink.ui.pane.PaneCard.MEDIA
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Fuel card in the pane's deck (brief of 11 Nov, stage A). */
class FuelDeckTest {
    @Test
    fun `the Fuel card is dealt after the route`() {
        assertEquals(listOf(DIRECTIONS, FUEL, MEDIA, CLOCK), PaneDeck.pages(navigating = true, hasTrack = true, fuel = true))
        assertEquals(listOf(FUEL, CLOCK), PaneDeck.pages(navigating = false, hasTrack = false, fuel = true))
        assertEquals(listOf(MEDIA, CLOCK), PaneDeck.pages(navigating = false, hasTrack = true, fuel = false))
        assertEquals("Drift + Tide (owner, 12 Nov)", PaneBackdrop.TIDE, PaneBackdrop.of(FUEL))
    }

    @Test
    fun `it comes by a swipe or low fuel, never as the default or beside CarPlay's app`() {
        assertEquals(CLOCK, PaneDeck.default(listOf(FUEL, CLOCK)))
        assertEquals(DIRECTIONS, PaneDeck.default(listOf(DIRECTIONS, FUEL, CLOCK)))
        assertEquals(MEDIA, PaneDeck.default(listOf(FUEL, MEDIA, CLOCK)))
        assertEquals("Now Playing in front, no route: the clock", CLOCK, PaneDeck.beside(MEDIA, listOf(FUEL, MEDIA, CLOCK)))
        assertEquals(MEDIA, PaneDeck.beside(DIRECTIONS, listOf(DIRECTIONS, FUEL, MEDIA, CLOCK)))
    }

    @Test
    fun `low fuel brings the Fuel card up, and the card it replaced comes back`() {
        val deck = listOf(FUEL, MEDIA, CLOCK)
        assertEquals(FUEL, LowFuelDeck.onLow(deck, MEDIA))
        assertEquals("not dealt: nothing moves", MEDIA, LowFuelDeck.onLow(listOf(MEDIA, CLOCK), MEDIA))
        assertEquals(MEDIA, LowFuelDeck.onCleared(before = MEDIA, shown = FUEL, pages = deck))
        assertEquals("the driver swiped away: their card stays", CLOCK, LowFuelDeck.onCleared(before = MEDIA, shown = CLOCK, pages = deck))
        assertEquals("the card it replaced went away", FUEL, LowFuelDeck.onCleared(before = DIRECTIONS, shown = FUEL, pages = deck))
        assertEquals("it was already up", FUEL, LowFuelDeck.onCleared(before = FUEL, shown = FUEL, pages = deck))
        assertEquals(FUEL, LowFuelDeck.onCleared(before = null, shown = FUEL, pages = deck))
    }
}
