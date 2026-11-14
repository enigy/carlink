package com.carlink.fuel

import com.carlink.ui.settings.FuelGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Fuel discounts (Scott, 13-14 Nov): the driver's own cents off at the stations whose program they're in. Prices made up. */
class FuelDiscountsTest {
    private val regular = CarFuel(CarFuel.Kind.GASOLINE, FuelGrade.REGULAR)
    private val diesel = CarFuel(CarFuel.Kind.DIESEL)

    private fun st(
        name: String,
        km: Double,
        price: Double,
        currency: String = "USD",
        street: String? = null,
    ) = Station(
        name,
        name,
        null,
        GeoPoint(km / 111.195, 0.0),
        prices = mapOf("REGULAR_UNLEADED" to FuelPrice(price, currency, null), "DIESEL" to FuelPrice(price + 0.5, currency, null)),
        street = street,
    )

    private fun matches(
        brand: DiscountBrand,
        vararg names: String,
    ) = names.filter { brand.matches(it) }

    @Test
    fun `each listed brand matches its own station names, joined and hyphenated ones too`() {
        assertEquals(5, matches(DiscountBrand.QUIKTRIP, "QuikTrip", "QuikTrip #712", "QT", "QT 1023", "Quik Trip").size)
        assertEquals(4, matches(DiscountBrand.SHELL, "Shell", "Shell #42", "Shell Gas Station", "Shell (Main St)").size)
        assertEquals(4, matches(DiscountBrand.EXXON_MOBIL, "Exxon", "Mobil", "ExxonMobil", "Exxon Mobil Food Mart").size)
        assertEquals(3, matches(DiscountBrand.BP, "BP", "BP Connect", "Amoco").size)
        assertEquals(3, matches(DiscountBrand.CHEVRON_TEXACO, "Chevron", "Texaco", "ChevronTexaco").size)
        assertEquals(3, matches(DiscountBrand.CIRCLE_K, "Circle K", "Circle-K #2741", "CircleK").size)
        assertEquals(3, matches(DiscountBrand.MURPHY_USA, "Murphy USA", "Murphy USA #7123", "Murphy Express").size)
        assertEquals(2, matches(DiscountBrand.RACETRAC, "RaceTrac", "Race Trac").size)
    }

    @Test
    fun `an ordinary word in another station's name takes no discount (the Mac's 13 Nov review, item 1)`() {
        assertEquals(emptyList<String>(), matches(DiscountBrand.MURPHY_USA, "Murphy's Market", "Murphy Oil Service", "Joe Murphy Gas"))
        assertEquals(emptyList<String>(), matches(DiscountBrand.SHELL, "Sea Shell Market", "Shell Rock Fuel", "Shellbrook Gas"))
        assertEquals(emptyList<String>(), matches(DiscountBrand.QUIKTRIP, "Kwik Trip", "QT Kitchens", "Cutie's Gas"))
        assertEquals(emptyList<String>(), matches(DiscountBrand.EXXON_MOBIL, "Automobile Service", "Mobile Gas"))
        assertEquals(emptyList<String>(), matches(DiscountBrand.BP, "BPX Fuel"))
        assertFalse(
            "not listed: the posted price is the member price",
            DiscountBrand.entries.any { it.matches("Costco Gasoline") || it.matches("Sam's Club") },
        )
    }

    @Test
    fun `Kroger counts at its banners' fuel centers, never at a surname (Scott, 13 Nov)`() {
        val fuel =
            listOf(
                "Kroger",
                "Kroger Fuel Center",
                "Kroger Marketplace Fuel Center",
                "King Soopers Fuel Center",
                "Fry's Fuel Center",
                "Fry’s Fuel Center",
                "Dillons Fuel Center",
                "Ralphs Fuel Center",
                "Smith's Fuel Center",
                "Fred Meyer Fuel",
                "Harris Teeter Fuel Center",
                "QFC Fuel Center",
            )
        assertEquals(fuel, matches(DiscountBrand.KROGER, *fuel.toTypedArray()))
        assertEquals(
            emptyList<String>(),
            matches(DiscountBrand.KROGER, "Smith's Auto Service", "Fry's Electronics", "Ralphs", "Dillons Towing", "Fred Meyer Jewelers"),
        )
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
    }

    @Test
    fun `a tie in the paid price goes to the nearer station (item 4)`() {
        // Shell $2.889 at 0.5 mi, QuikTrip $2.989 less 10 cents at 4 mi: the same $2.889, so the nearer one first.
        val stations = listOf(st("QuikTrip", 6.4, 2.989), st("Shell", 0.8, 2.889))
        val rows =
            StationList.rows(stations, regular, GeoPoint(0.0, 0.0), null, cheapestFirst = true, discounts = listOf(FuelDiscount(DiscountBrand.QUIKTRIP, "", 10)))
        assertEquals(listOf("Shell", "QuikTrip"), rows.map { it.station.name })
        assertTrue(rows.first().cheapest)
    }

    @Test
    fun `a discounted price reads in tenths of a cent, with the posted one beside it`() {
        val paid = FuelDiscounts.apply(FuelPrice(2.989, "USD", null), FuelDiscount(DiscountBrand.SHELL, "", 10))
        assertEquals("$2.889", StationText.price(paid.amount, paid.currency, Locale.US))
        assertEquals("$2.989", StationText.price(2.989, "USD", Locale.US))
        assertTrue(StationText.posted(FuelPrice(2.989, "USD", null)).endsWith(" posted"))
    }

    @Test
    fun `diesel too is on unless switched off, and the largest of two programs wins`() {
        val station = st("Shell", 1.0, 3.00)
        assertTrue("on for a new program", FuelDiscount(DiscountBrand.SHELL, "", 5).allFuels)
        val gasOnly = FuelDiscount(DiscountBrand.SHELL, "", 5, allFuels = false)
        assertNull(FuelDiscounts.forStation(station, diesel, listOf(gasOnly)))
        val both = gasOnly.copy(allFuels = true)
        assertEquals(both, FuelDiscounts.forStation(station, diesel, listOf(both)))
        val other = FuelDiscount(null, "shell", 8)
        assertEquals(other, FuelDiscounts.forStation(station, regular, listOf(gasOnly, other)))
        assertEquals(0.0, FuelDiscounts.apply(FuelPrice(0.05, "USD", null), gasOnly).amount, 1e-9)
    }

    @Test
    fun `only a US price per gallon takes a discount (item 6)`() {
        val shell = FuelDiscount(DiscountBrand.SHELL, "", 10)
        assertNull("a Canadian price per litre", FuelDiscounts.forStation(st("Shell", 1.0, 1.459, currency = "CAD"), regular, listOf(shell)))
        assertEquals(shell, FuelDiscounts.forStation(st("Shell", 1.0, 2.989), regular, listOf(shell)))
    }

    @Test
    fun `another station matches by the name typed, with its street or punctuation (item 5)`() {
        val local = FuelDiscount(null, "Kum & Go", 7)
        assertTrue(local.matches(st("Kum & Go #512", 1.0, 3.0)))
        assertFalse(local.matches(st("Kum", 1.0, 3.0)))
        val typedAsShown = FuelDiscount(null, "Demo Fuel North on Hwy 123", 26)
        assertTrue(typedAsShown.matches(st("Demo Fuel North", 1.0, 3.0, street = "Hwy 123")))
        assertTrue(FuelDiscount(null, "Gas (Main St)", 5).matches(st("Gas (Main St)", 1.0, 3.0)))
        assertNull(FuelDiscounts.forStation(st("Kum & Go", 1.0, 3.0), regular, listOf(local.copy(cents = 0))))
    }

    @Test
    fun `kept as one string, with nothing but the program and its amount`() {
        val list = listOf(FuelDiscount(DiscountBrand.QUIKTRIP, "", 10), FuelDiscount(null, "Kum\t& Go", 7, allFuels = false))
        val back = FuelDiscounts.decode(FuelDiscounts.encode(list))
        assertEquals(listOf(list[0], FuelDiscount(null, "Kum & Go", 7, allFuels = false)), back)
        assertTrue(FuelDiscounts.decode(null).isEmpty())
        assertTrue("an unknown brand is left out", FuelDiscounts.decode("nosuch\t\t5\tfalse").isEmpty())
        assertEquals(FuelDiscounts.MAX_CENTS, FuelDiscounts.decode("shell\t\t999\tfalse").single().cents)
        assertEquals("10¢ off every fuel", FuelDiscounts.describe(list[0]))
    }
}
