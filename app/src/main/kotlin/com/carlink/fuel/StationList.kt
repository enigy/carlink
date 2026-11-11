package com.carlink.fuel

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.roundToInt

/** One row of the Fuel card's list: the station, how far, whether the range reaches it, and its tags. */
data class StationRow(
    val station: Station,
    val distanceM: Double?,
    val outOfReach: Boolean,
    val cheapest: Boolean = false,
    val nearest: Boolean = false,
)

/**
 * The card's rows (the brief of 11 Nov). [cheapestFirst] (parked, the fuel not low): cheapest first with the cheapest and
 * the nearest tagged, and stations without a price last; otherwise (low fuel, moving) nearest first. A station out of
 * reach is marked for greying ([Geo.outOfReach]). Without prices (OpenStreetMap), nearest first, the ones known to sell
 * the fuel ahead.
 */
object StationList {
    fun rows(
        stations: List<Station>,
        here: GeoPoint?,
        rangeM: Float?,
        cheapestFirst: Boolean,
        max: Int,
    ): List<StationRow> {
        val all =
            stations.map { s ->
                val d = here?.let { Geo.metres(it, s.at) }
                StationRow(s, d, d != null && Geo.outOfReach(d, rangeM))
            }
        val byPrice = cheapestFirst && all.any { it.station.price != null }
        val ordered =
            when {
                byPrice -> all.sortedWith(compareBy({ it.station.price == null }, { it.station.price }, { it.distanceM }))
                here != null -> all.sortedWith(compareBy({ it.station.sellsFuel != true }, { it.distanceM }))
                else -> all
            }
        if (!byPrice) return ordered.take(max)
        val cheapest = ordered.first()
        val nearest = ordered.filter { it.distanceM != null }.minByOrNull { it.distanceM ?: Double.MAX_VALUE }
        return ordered.take(max).map { it.copy(cheapest = it === cheapest, nearest = it === nearest) }
    }
}

/** The words in a station row: distance, price and its age. */
object StationText {
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
