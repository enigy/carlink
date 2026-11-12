package com.carlink.fuel

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A position, degrees. */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
)

/** A station's price for one fuel: per US gallon in the US and per litre elsewhere, in [currency], reported at [atMs] (wall clock). */
data class FuelPrice(
    val amount: Double,
    val currency: String?,
    val atMs: Long?,
)

/**
 * A place to fill up, from a [FuelPriceSource], kept in memory only. Its source's facts are kept whole and the card picks
 * from them for the fuel it shows, so a change of grade, engine or the car's fuel type needs no lookup (the Mac's 11 Nov
 * bench): [prices] by Google's fuel type ([CarFuel.googleType]), and [diesel], OpenStreetMap's tag (yes true, no false,
 * not said null). [street]: the street it's on, for "Shell on Hwy 123". [credit]: its source's attribution, shown
 * wherever the station is.
 */
data class Station(
    val id: String,
    val name: String,
    val address: String?,
    val at: GeoPoint,
    val prices: Map<String, FuelPrice> = emptyMap(),
    val diesel: Boolean? = null,
    val street: String? = null,
    val credit: String? = null,
) {
    /** The price for [fuel], or null when the source has none. */
    fun priceFor(fuel: CarFuel): FuelPrice? = fuel.googleType?.let { prices[it] }

    /**
     * Whether it sells [fuel], for diesel (the diesel rule: the ones known to sell it go first, one that says no is left
     * out): a diesel price says yes, else OpenStreetMap's tag, else not said (null). Every station sells gasoline as far
     * as the sources go, so for gasoline it is null and the nearest comes first, priced or not.
     */
    fun sells(fuel: CarFuel): Boolean? =
        when {
            fuel.kind != CarFuel.Kind.DIESEL -> null
            priceFor(fuel) != null -> true
            else -> diesel
        }
}

/** Straight-line distance, and whether the range reaches a station (the brief of 11 Nov). */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val ROAD_FACTOR = 1.3

    /** Great-circle metres between [a] and [b]. */
    fun metres(
        a: GeoPoint,
        b: GeoPoint,
    ): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Out of reach: the road there, taken as 1.3 times the straight line, is longer than the range. Unknown range reaches. */
    fun outOfReach(
        straightM: Double,
        rangeM: Float?,
    ): Boolean = rangeM != null && straightM * ROAD_FACTOR > rangeM
}

/**
 * CarPlay directions to a place: `maps:?daddr=<lat>,<lon>&dirflg=d`, which the Mac benched on 11 Nov to open CarPlay's
 * route preview with GO (and Add Stop on a route already running). The coordinates are written with [Locale.ROOT] and six
 * decimals, about 10 cm, since a locale's decimal comma would break the link; a name in `&q=` is ignored, so none is sent.
 */
object Directions {
    /** The box's limit for a `requestUI` URL (OCBM `REQUEST_UI_URL_MAX`). */
    const val MAX_BYTES = 255

    fun url(at: GeoPoint): String = "maps:?daddr=${degrees(at.lat)},${degrees(at.lon)}&dirflg=d"

    private fun degrees(d: Double): String = String.format(Locale.ROOT, "%.6f", d)
}
