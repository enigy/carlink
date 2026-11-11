package com.carlink.fuel

import java.io.IOException

/**
 * Where the Fuel card's stations come from (the brief of 11 Nov): [OverpassSource] (Free, OpenStreetMap, no prices),
 * [DemoSource] (debug builds only, made-up stations and prices) and, in stage C, Google Places with the driver's own key.
 * Nearby stations off is no source at all.
 */
interface FuelPriceSource {
    /** For logs and Settings: "OpenStreetMap", "Demo". */
    val name: String

    /** What the card must show with this source's stations ("© OpenStreetMap contributors"), or null. */
    val attribution: String?

    /**
     * Stations within [radiusM] of [here] that sell [fuel], nearest first with the ones known to sell it ahead. Blocking:
     * call it off the main thread. Throws [IOException] when the source can't be reached.
     */
    @Throws(IOException::class)
    fun stationsNear(
        here: GeoPoint,
        fuel: CarFuel,
        radiusM: Double,
    ): List<Station>
}

/** Nearest first, with the stations known to sell the fuel ahead of the ones whose tags don't say (the diesel rule). */
internal fun List<Station>.nearestFirst(here: GeoPoint): List<Station> = sortedWith(compareBy({ it.sellsFuel != true }, { Geo.metres(here, it.at) }))
