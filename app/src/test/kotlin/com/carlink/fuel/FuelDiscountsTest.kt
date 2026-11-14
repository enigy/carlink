package com.carlink.fuel

import com.carlink.ui.settings.FuelGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fuel discounts (Scott, 14 Nov): the driver's own cents off at the stations whose program they're in. Prices made up. */
class FuelDiscountsTest {
    private val regular = CarFuel(CarFuel.Kind.GASOLINE, FuelGrade.REGULAR)
    private val diesel = CarFuel(CarFuel.Kind.DIESEL)

    private fun st(
        name: String,
        km: Double,
        price: Double,
    ) = Station(
        name,
        name,
        null,
        GeoPoint(km / 111.195, 0.0),
        prices = mapOf("REGULAR_UNLEADED" to FuelPrice(price, "USD", null), "DIESEL" to FuelPrice(price + 0.5, "USD", null)),
    )

    @Test
    fun `each listed brand matches its stations by name and no other`() {
        assertTrue(DiscountBrand.QUIKTRIP.matches("QuikTrip"))
        assertTrue(DiscountBrand.QUIKTRIP.matches("QT 1023"))
        assertFalse(DiscountBrand.QUIKTRIP.matches("Kwik Trip"))
        assertTrue(DiscountBrand.EXXON_MOBIL.matches("Mobil"))
        assertFalse("not a word", DiscountBrand.EXXON_MOBIL.matches("Automobile Service"))
        assertTrue(DiscountBrand.BP.matches("Amoco"))
        assertTrue(DiscountBrand.CIRCLE_K.matches("Circle K #2741"))
        assertTrue(DiscountBrand.RACETRAC.matches("RaceTrac"))
        assertTrue(DiscountBrand.MURPHY_USA.matches("Murphy Express"))
        assertFalse("not listed: the posted price is the member price", DiscountBrand.entries.any { it.matches("Costco Gasoline") || it.matches("Sam's Club") })
    }

    @Test
    fun `the discount ranks a station at what the driver pays, with its posted price kept`() {
        val stations = listOf(st("Shell", 1.0, 2.95), st("QuikTrip", 2.0, 2.99))
        val qt = FuelDiscount(DiscountBrand.QUIKTRIP, "", 10)
        val rows = StationList.rows(stations, regular, GeoPoint(0.0, 0.0), null, cheapestFirst = true, discounts = listOf(qt))
        assertEquals("QuikTrip", rows.first().station.name)
        assertEquals(2.89, rows.first().price!!.amount, 1e-9)
        assertEquals(2.99, rows.first().posted!!.amount, 1e-9)
        assertEquals(qt, rows.first().discount)
        assertTrue(rows.first().cheapest)
        assertNull("not in a program", rows[1].discount)
        assertTrue(StationText.posted(rows.first().posted!!).endsWith(" posted"))
    }

    @Test
    fun `gasoline only unless the program says diesel too, and the largest of two`() {
        val station = st("Shell", 1.0, 3.00)
        val gas = FuelDiscount(DiscountBrand.SHELL, "", 5)
        assertNull(FuelDiscounts.forStation(station, diesel, listOf(gas)))
        val both = gas.copy(allFuels = true)
        assertEquals(both, FuelDiscounts.forStation(station, diesel, listOf(both)))
        val other = FuelDiscount(null, "shell", 8)
        assertEquals(other, FuelDiscounts.forStation(station, regular, listOf(gas, other)))
        assertEquals(0.0, FuelDiscounts.apply(FuelPrice(0.05, "USD", null), gas).amount, 1e-9)
    }

    @Test
    fun `another station matches by the name typed, and blank amounts are no program`() {
        val local = FuelDiscount(null, "Kum & Go", 7)
        assertTrue(local.matches("Kum & Go #512"))
        assertFalse(local.matches("Kum"))
        assertNull(FuelDiscounts.forStation(st("Kum & Go", 1.0, 3.0), regular, listOf(local.copy(cents = 0))))
    }

    @Test
    fun `kept as one string, with nothing but the program and its amount`() {
        val list = listOf(FuelDiscount(DiscountBrand.QUIKTRIP, "", 10), FuelDiscount(null, "Kum\t& Go", 7, allFuels = true))
        val back = FuelDiscounts.decode(FuelDiscounts.encode(list))
        assertEquals(listOf(list[0], FuelDiscount(null, "Kum & Go", 7, allFuels = true)), back)
        assertTrue(FuelDiscounts.decode(null).isEmpty())
        assertTrue("an unknown brand is left out", FuelDiscounts.decode("nosuch\t\t5\tfalse").isEmpty())
        assertEquals(FuelDiscounts.MAX_CENTS, FuelDiscounts.decode("shell\t\t999\tfalse").single().cents)
        assertEquals("10¢ off gasoline", FuelDiscounts.describe(list[0]))
    }
}
