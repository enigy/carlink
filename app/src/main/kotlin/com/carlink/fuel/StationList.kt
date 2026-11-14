package com.carlink.fuel

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * One row of the Fuel card's list: the station, its [price] for the card's fuel, how far, whether the range reaches it,
 * its tags, and whether the driver [starred] it. With a discount program the driver is in ([discount]), [price] is what
 * the driver pays and [posted] the station's own price.
 */
data class StationRow(
    val station: Station,
    val price: FuelPrice?,
    val distanceM: Double?,
    val outOfReach: Boolean,
    val cheapest: Boolean = false,
    val nearest: Boolean = false,
    val starred: Boolean = false,
    val posted: FuelPrice? = null,
    val discount: FuelDiscount? = null,
)

/**
 * The card's rows (the brief of 11 Nov), for the car's [CarFuel] as it is now: its price at each station, and without a
 * station that says it doesn't sell it. [cheapestFirst] (parked, the fuel not low): cheapest first with the cheapest and
 * the nearest tagged, and stations without a price last; otherwise (low fuel, moving) nearest first, for diesel the ones
 * known to sell it ahead ([Station.sells]). A station out of reach is marked for greying ([Geo.outOfReach]). The card
 * shows the first few. A station in one of the driver's [discounts] is priced, and ranked, at what the driver pays.
 */
object StationList {
    private const val MILLS = 1000.0

    @Suppress("LongParameterList") // The card's inputs as they are, each one of its rules; a holder would only rename them.
    fun rows(
        stations: List<Station>,
        fuel: CarFuel,
        here: GeoPoint?,
        rangeM: Float?,
        cheapestFirst: Boolean,
        discounts: List<FuelDiscount> = emptyList(),
    ): List<StationRow> {
        val all =
            stations
                .filter { it.sells(fuel) != false }
                .map { s ->
                    val d = here?.let { Geo.metres(it, s.at) }
                    val posted = s.priceFor(fuel)
                    val discount = posted?.let { FuelDiscounts.forStation(s, fuel, discounts) }
                    StationRow(
                        s,
                        posted?.let { FuelDiscounts.apply(it, discount) },
                        d,
                        d != null && Geo.outOfReach(d, rangeM),
                        posted = posted,
                        discount = discount,
                    )
                }
        val byPrice = cheapestFirst && all.any { it.price != null }
        val ordered =
            when {
                // In tenths of a cent, as the card shows prices: a discounted price (posted less cents/100) and an equal posted
                // one differ in floating-point noise, which ranked a far station first at the same price (the Mac, 13 Nov).
                byPrice -> all.sortedWith(compareBy({ it.price == null }, { it.price?.let { p -> (p.amount * MILLS).roundToLong() } }, { it.distanceM }))
                here != null -> all.sortedWith(compareBy({ it.station.sells(fuel) != true }, { it.distanceM }))
                else -> all
            }
        if (!byPrice) return ordered
        val cheapest = ordered.first()
        val nearest = ordered.filter { it.distanceM != null }.minByOrNull { it.distanceM ?: Double.MAX_VALUE }
        return ordered.map { it.copy(cheapest = it === cheapest, nearest = it === nearest) }
    }
}

/** The words in a station row: its name, distance, price and its age. */
object StationText {
    /** The station's street, when the source has one and the name doesn't already say it. */
    fun street(station: Station): String? = station.street?.takeUnless { station.name.contains(it, ignoreCase = true) }

    /** "Shell on Hwy 123": the name, then its [street]. */
    fun title(station: Station): String = street(station)?.let { "${station.name} on $it" } ?: station.name

    private const val METRES_PER_MILE = 1609.344
    private const val METRES_PER_KM = 1000.0
    private const val TENTHS_BELOW = 10.0
    private const val MS_PER_MIN = 60_000L
    private const val MIN_PER_HOUR = 60L
    private const val HOURS_PER_DAY = 24L

    /** "0.4 mi", "12 mi"; "3.7 km", "19 km": tenths below ten. */
    fun distance(
        m: Double,
        imperial: Boolean,
    ): String {
        val v = m / if (imperial) METRES_PER_MILE else METRES_PER_KM
        val unit = if (imperial) "mi" else "km"
        return if (v < TENTHS_BELOW) String.format(Locale.getDefault(), "%.1f %s", v, unit) else "${v.roundToInt()} $unit"
    }

    /** The price in its currency, with a third decimal where it has one ($3.459), as the pumps show it. */
    fun price(
        price: Double,
        currency: String?,
        locale: Locale = Locale.getDefault(),
    ): String {
        val f = NumberFormat.getCurrencyInstance(locale)
        currency?.let { code -> runCatching { f.currency = Currency.getInstance(code) } }
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 3
        return f.format(price)
    }

    /** A discounted row's station price: "$2.989 posted". */
    fun posted(price: FuelPrice): String = "${price(price.amount, price.currency)} posted"

    /** How old a price is: "just now", "12 min ago", "2 h ago", "3 days ago". */
    fun age(
        atMs: Long,
        nowMs: Long,
    ): String {
        val min = ((nowMs - atMs) / MS_PER_MIN).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < MIN_PER_HOUR -> "$min min ago"
            min < MIN_PER_HOUR * HOURS_PER_DAY -> "${min / MIN_PER_HOUR} h ago"
            else -> (min / (MIN_PER_HOUR * HOURS_PER_DAY)).let { d -> if (d == 1L) "1 day ago" else "$d days ago" }
        }
    }

    /** A price older than a day is marked on the card. */
    fun stale(
        atMs: Long,
        nowMs: Long,
    ): Boolean = nowMs - atMs > MIN_PER_HOUR * HOURS_PER_DAY * MS_PER_MIN
}
