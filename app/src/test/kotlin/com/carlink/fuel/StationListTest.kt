package com.carlink.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The card's rows and their words (the brief of 11 Nov). Stations, prices and places made up. */
class StationListTest {
    private val here = GeoPoint(0.0, 0.0)

    /** A station about [km] kilometres north. */
    private fun st(
        id: String,
        km: Double,
        price: Double? = null,
    ) = Station(id, id, null, GeoPoint(km / 111.195, 0.0), price = price, currency = "USD")

    private val list = listOf(st("near", 1.0, 3.50), st("cheap", 4.0, 3.20), st("none", 0.5), st("far", 20.0, 3.30))

    @Test
    fun `parked, cheapest first with tags, and no price last`() {
        val rows = StationList.rows(list, here, rangeM = 100_000f, cheapestFirst = true, max = 4)
        assertEquals(listOf("cheap", "far", "near", "none"), rows.map { it.station.id })
        assertTrue(rows[0].cheapest)
        assertTrue("the nearest is tagged where it sits", rows[3].nearest)
        assertFalse(rows[1].cheapest || rows[1].nearest)
    }

    @Test
    fun `low fuel or moving, nearest first, out of reach marked`() {
        val low = StationList.rows(list, here, rangeM = 10_000f, cheapestFirst = false, max = 4)
        assertEquals(listOf("none", "near", "cheap", "far"), low.map { it.station.id })
        assertEquals(listOf(false, false, false, true), low.map { it.outOfReach })
        assertFalse("no tags", low.any { it.cheapest || it.nearest })
        assertEquals(3, StationList.rows(list, here, null, cheapestFirst = false, max = 3).size)
    }

    @Test
    fun `row words`() {
        assertEquals("0.6 mi", StationText.distance(1_000.0, imperial = true).replace(',', '.'))
        assertEquals("12 mi", StationText.distance(19_312.0, imperial = true))
        assertEquals("19 km", StationText.distance(19_312.0, imperial = false))
        assertEquals("$3.459", StationText.price(3.459, "USD", Locale.US))
        assertEquals("$3.40", StationText.price(3.4, "USD", Locale.US))
        val now = 10L * 24 * 3_600_000
        assertEquals("just now", StationText.age(now - 30_000, now))
        assertEquals("12 min ago", StationText.age(now - 12 * 60_000, now))
        assertEquals("2 h ago", StationText.age(now - 2 * 3_600_000, now))
        assertEquals("1 day ago", StationText.age(now - 26 * 3_600_000, now))
        assertEquals("3 days ago", StationText.age(now - 3 * 24 * 3_600_000, now))
        assertTrue(StationText.stale(now - 25 * 3_600_000, now))
        assertFalse(StationText.stale(now - 23 * 3_600_000, now))
    }

    @Test
    fun `the demo source makes stations around the car, nearest first, inside the radius`() {
        val demo = DemoSource(nowMs = { 1_000_000_000L }).stationsNear(here, CarFuel(CarFuel.Kind.DIESEL), radiusM = 8_046.72)
        assertEquals(5, demo.size)
        val d = demo.map { Geo.metres(here, it.at) }
        assertEquals(d.sorted(), d)
        assertTrue(d.all { it <= 8_046.72 })
        assertEquals(2, DemoSource().stationsNear(here, CarFuel(CarFuel.Kind.DIESEL), radiusM = 3_100.0).size)
    }
}
