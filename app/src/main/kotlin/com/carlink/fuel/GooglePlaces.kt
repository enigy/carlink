package com.carlink.fuel

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/**
 * Google Places API (New) Nearby Search for the Fuel card (the brief of 11 Nov, stage C), with the driver's own key:
 * `POST https://places.googleapis.com/v1/places:searchNearby`, gas stations within the radius ranked by distance, and
 * for each the field mask's id, name, address and its parts (the street, for "Shell on Hwy 123"), location and
 * `fuelOptions` (the last known price per fuel type, with its `updateTime`). Asking for `fuelOptions` bills the call at
 * the Nearby Search Enterprise + Atmosphere rate, so the card counts every lookup ([MonthlyBudget]). Nothing Google sends is kept past the session; the card shows "Google
 * Maps" with it. Plain Kotlin: the request and the answer are tested without a network.
 */
object GooglePlaces {
    const val ENDPOINT = "https://places.googleapis.com/v1/places:searchNearby"
    const val FIELD_MASK = "places.id,places.displayName,places.formattedAddress,places.addressComponents,places.location,places.fuelOptions"
    private const val MAX_RESULTS = 20
    private const val NANOS = 1e9

    /** Google's own cap for the circle's radius. */
    const val MAX_RADIUS_M = 50_000.0

    /** The request body: gas stations within [radiusM] of [here], nearest first, at most twenty (Google's most; a lookup costs the same either way). */
    fun body(
        here: GeoPoint,
        radiusM: Double,
    ): String {
        val center = JSONObject().put("latitude", here.lat).put("longitude", here.lon)
        val circle = JSONObject().put("center", center).put("radius", radiusM.coerceIn(1.0, MAX_RADIUS_M))
        return JSONObject()
            .put("includedTypes", JSONArray().put("gas_station"))
            .put("maxResultCount", MAX_RESULTS)
            .put("rankPreference", "DISTANCE")
            .put("locationRestriction", JSONObject().put("circle", circle))
            .toString()
    }

    /** The answer to stations, each with every price Google has for it, by fuel type ([Station.prices]). */
    fun parse(json: String): List<Station> {
        val places = JSONObject(json).optJSONArray("places") ?: return emptyList()
        return (0 until places.length()).mapNotNull { i -> station(places.getJSONObject(i)) }
    }

    private fun station(p: JSONObject): Station? {
        val loc = p.optJSONObject("location") ?: return null
        if (!loc.has("latitude") || !loc.has("longitude")) return null
        val name = p.optJSONObject("displayName")?.optString("text")?.ifBlank { null } ?: "Fuel station"
        val prices = p.optJSONObject("fuelOptions")?.optJSONArray("fuelPrices")
        return Station(
            id = "google:${p.optString("id")}",
            name = name,
            address = p.optString("formattedAddress").ifBlank { null },
            at = GeoPoint(loc.getDouble("latitude"), loc.getDouble("longitude")),
            prices = (0 until (prices?.length() ?: 0)).mapNotNull { i -> prices?.optJSONObject(i)?.let { priceOf(it) } }.toMap(),
            street = streetOf(p),
        )
    }

    /** The street, from `addressComponents`: the "route" part's short text ("Hwy 123"), else its long text, else null. */
    private fun streetOf(p: JSONObject): String? {
        val parts = p.optJSONArray("addressComponents") ?: return null
        val route = (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }.firstOrNull { it.optJSONArray("types").holds("route") }
        return route?.let { it.optString("shortText").ifBlank { null } ?: it.optString("longText").ifBlank { null } }
    }

    private fun JSONArray?.holds(value: String): Boolean = this != null && (0 until length()).any { optString(it) == value }

    /** One `fuelOptions.fuelPrices` entry: its type, and the amount (units plus nanos), currency and when it was updated. */
    private fun priceOf(entry: JSONObject): Pair<String, FuelPrice>? {
        val type = entry.optString("type").ifBlank { null } ?: return null
        val money = entry.optJSONObject("price") ?: return null
        val amount = (money.optString("units").toLongOrNull() ?: 0L) + money.optLong("nanos") / NANOS
        val at = runCatching { Instant.parse(entry.optString("updateTime")).toEpochMilli() }.getOrNull()
        return if (amount > 0.0) type to FuelPrice(amount, money.optString("currencyCode").ifBlank { null }, at) else null
    }

    /**
     * Google's error answer in the driver's words, for the card and Settings' Test: the ErrorInfo `reason` first, then
     * the HTTP status. Never the key.
     */
    fun problem(
        code: Int,
        json: String?,
    ): String {
        val error = json?.let { runCatching { JSONObject(it).optJSONObject("error") }.getOrNull() }
        val details = error?.optJSONArray("details")
        val reasons = (0 until (details?.length() ?: 0)).mapNotNull { details?.optJSONObject(it)?.optString("reason")?.ifBlank { null } }
        val message = error?.optString("message").orEmpty()
        return when {
            "API_KEY_INVALID" in reasons || "API key not valid" in message -> "Google says this key isn't valid. Check it was copied whole."
            "SERVICE_DISABLED" in reasons -> "Places API (New) isn't turned on in this key's Google Cloud project."
            "BILLING_DISABLED" in reasons || "billing" in message.lowercase() -> "Billing isn't set up for this key's Google Cloud project."
            reasons.any { it.startsWith("API_KEY_") && it.endsWith("_BLOCKED") } -> "The key's restrictions don't allow Places API (New) from this app."
            code == HTTP_FORBIDDEN -> "Google refused the key (HTTP 403)."
            else -> "Google answered HTTP $code."
        }
    }

    private const val HTTP_FORBIDDEN = 403
}

/** Google refused the request for a reason the driver can fix ([GooglePlaces.problem]'s words): the key, the project or billing. */
class GoogleKeyProblem(
    val words: String,
) : IOException(words)

/**
 * Google answered HTTP 429: this key's quota is used up for now, usually the daily cap the driver set in Cloud Console,
 * which won't clear in minutes. The card stops asking Google for an hour and shows the free stations.
 */
class GoogleQuota(
    val why: String,
) : IOException(why)
