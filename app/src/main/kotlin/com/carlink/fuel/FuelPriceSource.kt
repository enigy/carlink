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

    /** Which server the last lookup asked (its host only), for the log; null where there is one. */
    val detail: String? get() = null

    /**
     * Stations within [radiusM] of [here], with what the source knows of each fuel there ([Station.prices],
     * [Station.diesel]): the card picks from it for the car's fuel ([StationList]). Blocking: call it off the main thread.
     * Throws [IOException] when the source can't be reached.
     */
    @Throws(IOException::class)
    fun stationsNear(
        here: GeoPoint,
        radiusM: Double,
    ): List<Station>
}

/** Nearest first. */
internal fun List<Station>.nearestFirst(here: GeoPoint): List<Station> = sortedBy { Geo.metres(here, it.at) }

/**
 * The station service answered that it is busy (HTTP 429 or 5xx) or took too long to answer: the card says so and tries
 * again later, after [retryAfterS] when the service said (the Mac's 11 Nov bench: overpass-api.de at capacity, 502s and
 * answers 25 to 32 s late). [why] is the status or "timeout", for the log.
 */
class SourceBusy(
    val why: String,
    val retryAfterS: Long? = null,
    cause: Throwable? = null,
) : IOException(why, cause)

/** The service couldn't be reached at all: no internet, or no route to it. The card says "No internet". */
class SourceUnreachable(
    val why: String,
    cause: Throwable? = null,
) : IOException(why, cause)

/** An HTTP answer other than 200: busy for 429 and 5xx (with the `Retry-After` seconds when sent), else a plain failure. */
fun httpFailure(
    code: Int,
    retryAfter: String?,
): IOException {
    val busy = code == HTTP_TOO_MANY_REQUESTS || code >= HTTP_SERVER_ERROR
    return if (busy) SourceBusy("HTTP $code", retryAfter?.trim()?.toLongOrNull()) else IOException("HTTP $code")
}

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
