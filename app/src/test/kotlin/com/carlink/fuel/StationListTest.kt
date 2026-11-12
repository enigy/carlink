package com.carlink.fuel

import com.carlink.ui.settings.FuelGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The card's rows and their words (the brief of 11 Nov). Stations, prices and places made up. */
class StationListTest {
    private val here = GeoPoint(0.0, 0.0)
    private val regular = CarFuel(CarFuel.Kind.GASOLINE, FuelGrade.REGULAR)
    private val premium = CarFuel(CarFuel.Kind.GASOLINE, FuelGrade.PREMIUM)
    private val diesel = CarFuel(CarFuel.Kind.DIESEL)

    /** A station about [km] kilometres north, with a regular [price] and a [premiumPrice]. */
    private fun st(
        id: String,
        km: Double,
        price: Double? = null,
        premiumPrice: Double? = null,
        diesel: Boolean? = null,
    ): Station {
        val prices = listOfNotNull(price?.let { "REGULAR_UNLEADED" to FuelPrice(it, "USD", null) }, premiumPrice?.let { "PREMIUM" to FuelPrice(it, "USD", null) })
        return Station(id, id, null, GeoPoint(km / 111.195, 0.0), prices = prices.toMap(), diesel = diesel)
    }

    private val list = listOf(st("near", 1.0, 3.50), st("cheap", 4.0, 3.20), st("none", 0.5), st("far", 20.0, 3.30))

    @Test
    fun `parked, cheapest first with tags, and no price last`() {
        val rows = StationList.rows(list, regular, here, rangeM = 100_000f, cheapestFirst = true)
        assertEquals(listOf("cheap", "far", "near", "none"), rows.map { it.station.id })
        assertTrue(rows[0].cheapest)
        assertTrue("the nearest is tagged where it sits", rows[3].nearest)
        assertFalse(rows[1].cheapest || rows[1].nearest)
    }

    @Test
    fun `low fuel or moving, nearest first, out of reach marked`() {
        val low = StationList.rows(list, regular, here, rangeM = 10_000f, cheapestFirst = false)
        assertEquals(listOf("none", "near", "cheap", "far"), low.map { it.station.id })
        assertEquals(listOf(false, false, false, true), low.map { it.outOfReach })
        assertFalse("no tags", low.any { it.cheapest || it.nearest })
    }

    /** The Mac's 11 Nov bench: Regular -> Premium kept the regular prices until the next lookup. */
    @Test
    fun `a change of grade or fuel picks from the same stations, without a lookup`() {
        val two = listOf(st("a", 1.0, price = 3.20, premiumPrice = 4.30), st("b", 2.0, price = 3.30, premiumPrice = 4.00))
        val reg = StationList.rows(two, regular, here, rangeM = null, cheapestFirst = true)
        assertEquals(listOf("a", "b"), reg.map { it.station.id })
        assertEquals(3.20, reg[0].price!!.amount, 1e-9)
        val prem = StationList.rows(two, premium, here, rangeM = null, cheapestFirst = true)
        assertEquals("premium has another cheapest", listOf("b", "a"), prem.map { it.station.id })
        assertEquals(4.00, prem[0].price!!.amount, 1e-9)
        val tagged = listOf(st("unsaid", 0.5), st("yes", 2.0, diesel = true), st("no", 1.0, diesel = false))
        assertEquals("diesel: known first, a no left out", listOf("yes", "unsaid"), StationList.rows(tagged, diesel, here, null, cheapestFirst = false).map { it.station.id })
        assertEquals("gasoline: all, nearest first", listOf("unsaid", "no", "yes"), StationList.rows(tagged, regular, here, null, cheapestFirst = false).map { it.station.id })
    }

    @Test
    fun `row words`() {
        val shell = Station("x", "Shell", null, here, street = "Hwy 123")
        assertEquals("Shell on Hwy 123", StationText.title(shell))
        assertEquals("no street, the name alone", "Shell", StationText.title(shell.copy(street = null)))
        assertEquals("the name says it already", "Main St Gas", StationText.title(Station("y", "Main St Gas", null, here, street = "main st")))
        assertEquals("moving, the street beside the distance", "Hwy 123", StationText.street(shell))
        assertNull(StationText.street(Station("y", "Main St Gas", null, here, street = "main st")))
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
        val demo = DemoSource(nowMs = { 1_000_000_000L }).stationsNear(here, radiusM = 8_046.72)
        assertEquals(5, demo.size)
        val d = demo.map { Geo.metres(here, it.at) }
        assertEquals(d.sorted(), d)
        assertTrue(d.all { it <= 8_046.72 })
        assertEquals(2, DemoSource().stationsNear(here, radiusM = 3_100.0).size)
        val priced = demo.first { it.prices.isNotEmpty() }
        assertTrue("premium dearer than regular", priced.priceFor(premium)!!.amount > priced.priceFor(regular)!!.amount)
    }
}
