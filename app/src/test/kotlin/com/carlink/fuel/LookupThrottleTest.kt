package com.carlink.fuel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the Fuel card looks stations up (the brief of 11 Nov). Positions made up. */
class LookupThrottleTest {
    private val start = GeoPoint(-33.9, 151.2)

    /** About [km] kilometres north of [start]. */
    private fun north(km: Double) = GeoPoint(start.lat + km / 111.195, start.lon)

    @Test
    fun `none before the card has shown, then one when it does`() {
        val t = LookupThrottle()
        assertFalse(t.due(0, start, visible = false))
        assertFalse("low fuel before the card ever showed", t.due(0, start, visible = false, lowFuelRose = true))
        assertFalse("no position", t.due(0, null, visible = true))
        assertTrue(t.due(0, start, visible = true))
    }

    @Test
    fun `then only after 5 km or 15 minutes while the card is up`() {
        val t = LookupThrottle()
        t.due(0, start, visible = true)
        t.ran(0, start)
        assertFalse(t.due(60_000, north(4.5), visible = true))
        assertTrue(t.due(60_000, north(5.1), visible = true))
        assertTrue(t.due(LookupThrottle.AGE_MS, start, visible = true))
        assertFalse("hidden: it waits", t.due(LookupThrottle.AGE_MS, north(9.0), visible = false))
    }

    /** Owner, 12 Nov: the card's refresh. */
    @Test
    fun `the driver's refresh looks up past the 5 km and 15 minutes, with the card up`() {
        val t = LookupThrottle()
        t.due(0, start, visible = true)
        t.ran(0, start)
        assertFalse(t.due(60_000, start, visible = true))
        assertTrue(t.due(60_000, start, visible = true, asked = true))
        assertFalse("not with the card hidden", t.due(60_000, start, visible = false, asked = true))
        assertFalse("not without a position", t.due(60_000, null, visible = true, asked = true))
    }

    @Test
    fun `a refresh waits out a running lookup, the minute after one, and a busy service's own retry`() {
        assertTrue(RefreshGate.allows(now = 70_000, restUntil = 60_000, looking = false, waiting = false))
        assertTrue("never looked up yet", RefreshGate.allows(now = 0, restUntil = 0, looking = false, waiting = false))
        assertFalse("within the minute", RefreshGate.allows(now = 30_000, restUntil = 60_000, looking = false, waiting = false))
        assertFalse("during a lookup", RefreshGate.allows(now = 70_000, restUntil = 60_000, looking = true, waiting = false))
        assertFalse("during a busy wait", RefreshGate.allows(now = 70_000, restUntil = 60_000, looking = false, waiting = true))
        assertTrue("a minute and more", RefreshGate.REST_MS >= 60_000)
    }

    @Test
    fun `low fuel looks up at once, and a reset starts over`() {
        val t = LookupThrottle()
        t.due(0, start, visible = true)
        t.ran(0, start)
        assertTrue(t.due(1_000, start, visible = false, lowFuelRose = true))
        t.reset()
        assertFalse("hidden after a reset", t.due(2_000, start, visible = false))
        assertTrue(t.due(2_000, start, visible = true))
    }
}
