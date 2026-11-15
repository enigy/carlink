package com.carlink.fuel

import com.carlink.ui.settings.FuelGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals(6, matches(DiscountBrand.QUIKTRIP, "QuikTrip", "QuikTrip #712", "QT", "QT 1023", "QT #45", "Quik Trip").size)
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
        // The 14 Nov review, E2: a possessive is someone's, and "QT" plus a word may be anyone's.
        assertEquals(emptyList<String>(), matches(DiscountBrand.SHELL, "Shell's Garage", "Shell’s Garage"))
        assertEquals(emptyList<String>(), matches(DiscountBrand.QUIKTRIP, "QT's Liquor", "QT Mart", "QT Food Mart", "QT Express", "QT Gas"))
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
                "Fred Meyer Fuel Center",
                "Fry's Marketplace Fuel Center",
                "Ralph's Fuel Center",
                "Dillon's Fuel Center",
                "Harris Teeter Fuel Center",
                "QFC Fuel Center",
            )
        assertEquals(fuel, matches(DiscountBrand.KROGER, *fuel.toTypedArray()))
        assertEquals(
            emptyList<String>(),
            matches(
                DiscountBrand.KROGER,
                "Smith's Auto Service",
                "Fry's Electronics",
                "Ralphs",
                "Dillons Towing",
                "Fred Meyer Jewelers",
                // Not "<banner> Fuel Center", as decided (E2).
                "Smith's Fuel",
                "Smith's Fuel Stop",
                "Smith's Fuel & Food",
                "Smiths Fuel Mart",
                "Fry's Fuel Stop",
                "Frys Fuel",
                "Fry's Fuel & Food",
                "Ralphs Fuel & Propane",
                "Fred Meyer Fuel",
            ),
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
        // QuikTrip $2.502 less 10 cents at 4 mi is 2.4019999999999997 as a double, Shell $2.402 at 0.5 mi is 2.402: the same
        // price on the card, so the nearer one first (a pair the plain comparator orders the other way: E3).
        val stations = listOf(st("QuikTrip", 6.4, 2.502), st("Shell", 0.8, 2.402))
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
    fun `an E85 car follows the diesel switch, the posted price with it off, the discount with it on (CL-6)`() {
        val e85 = CarFuel(CarFuel.Kind.GASOLINE, FuelGrade.E85)
        val station = st("Shell", 1.0, 3.00).let { it.copy(prices = it.prices + ("E85" to FuelPrice(2.50, "USD", null))) }
        val off = FuelDiscount(DiscountBrand.SHELL, "", 10, allFuels = false)
        assertNull(FuelDiscounts.forStation(station, e85, listOf(off)))
        assertEquals(2.50, FuelDiscounts.apply(station.priceFor(e85)!!, null).amount, 1e-9)
        val on = off.copy(allFuels = true)
        assertEquals(on, FuelDiscounts.forStation(station, e85, listOf(on)))
        assertEquals(2.40, FuelDiscounts.apply(station.priceFor(e85)!!, on).amount, 1e-9)
        assertEquals(off, FuelDiscounts.forStation(station, regular, listOf(off)))
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
        val noCurrency = st("Shell", 1.0, 2.989).let { s -> s.copy(prices = s.prices.mapValues { (_, p) -> p.copy(currency = null) }) }
        assertNull("a price with no currency", FuelDiscounts.forStation(noCurrency, regular, listOf(shell)))
    }

    @Test
    fun `another station matches by the name typed, with its street or punctuation (item 5)`() {
        val local = FuelDiscount(null, "Kum & Go", 7)
        assertTrue(local.matches(st("Kum & Go #512", 1.0, 3.0)))
        assertFalse(local.matches(st("Kum", 1.0, 3.0)))
        val typedAsShown = FuelDiscount(null, "Demo Fuel North on Hwy 123", 26)
        assertTrue(typedAsShown.matches(st("Demo Fuel North", 1.0, 3.0, street = "Hwy 123")))
        assertTrue(FuelDiscount(null, "Gas (Main St)", 5).matches(st("Gas (Main St)", 1.0, 3.0)))
        // Never in a street alone (E1).
        assertFalse(FuelDiscount(null, "76", 5).matches(st("Shell", 1.0, 3.0, street = "CA-76")))
        assertFalse(FuelDiscount(null, "Speedway", 5).matches(st("Circle K", 1.0, 3.0, street = "E Speedway Blvd")))
        // The two apostrophes as one, whichever is typed, in the name and in the title (round 3).
        assertTrue(FuelDiscount(null, "Casey's", 5).matches(st("Casey’s", 1.0, 3.0)))
        assertTrue(FuelDiscount(null, "Casey’s", 5).matches(st("Casey's #12", 1.0, 3.0)))
        assertTrue(FuelDiscount(null, "Casey’s on Main St", 5).matches(st("Casey's", 1.0, 3.0, street = "Main St")))
        // Inside the name, where only the name's own fold finds it: the title doesn't start with it (CL-4).
        assertTrue(FuelDiscount(null, "Casey's", 5).matches(st("Ames Casey’s", 1.0, 3.0)))
        // A listed brand typed here matches as its row does, from the start (CL-3).
        val typedShell = FuelDiscount(null, "Shell", 5)
        assertTrue(typedShell.matches(st("Shell", 1.0, 3.0)))
        assertFalse(typedShell.matches(st("Sea Shell Market", 1.0, 3.0)))
        assertFalse(typedShell.matches(st("Shell Rock Fuel", 1.0, 3.0)))
        // The placeholder for an unnamed station is never a name (round 3).
        assertFalse(FuelDiscount(null, "Fuel station", 5).matches(st("Downtown Fuel Station", 1.0, 3.0)))
        assertFalse(FuelDiscount(null, "fuel station", 5).matches(st("Fuel station", 1.0, 3.0)))
        assertNull(FuelDiscounts.forStation(st("Kum & Go", 1.0, 3.0), regular, listOf(local.copy(cents = 0))))
    }

    @Test
    fun `the dialog says why it won't take a listed brand or the unnamed placeholder (CL-3, CL-7)`() {
        assertEquals("Shell has its own row: set it there", FuelDiscounts.nameProblem(" shell "))
        assertEquals("Kroger has its own row: set it there", FuelDiscounts.nameProblem("Fry's Fuel Center"))
        assertNotNull(FuelDiscounts.nameProblem("Fuel Station"))
        assertNull(FuelDiscounts.nameProblem("Kum & Go"))
        assertNull(FuelDiscounts.nameProblem("Shell Rock Fuel"))
    }

    @Test
    fun `the dialog offers named stations that aren't a listed brand (E4)`() {
        val around =
            listOf(st("Fuel station", 1.0, 3.0), st("QuikTrip", 1.0, 3.0), st("123", 1.0, 3.0), st("Kum & Go", 1.0, 3.0), st("Kum & Go", 2.0, 3.0))
        assertEquals(listOf("Kum & Go"), FuelDiscounts.pickable(around))
        // A name over the cap is left out, not cut (cut inside a word or number it misses its own station: round 3).
        val long = st("Speedy Gas & Go Travel Center Number 41237 Hwy 9", 1.0, 3.0)
        assertTrue(FuelDiscounts.pickable(listOf(long)).isEmpty())
        // Every pick matches the station it came from.
        val named = listOf(st("Kum & Go", 1.0, 3.0), st("Gas (Main St)", 1.0, 3.0), st("Casey’s #12", 1.0, 3.0), long)
        for (pick in FuelDiscounts.pickable(named)) {
            assertTrue(pick, named.any { FuelDiscount(null, pick, 5).matches(it) && it.name.trim() == pick })
        }
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
