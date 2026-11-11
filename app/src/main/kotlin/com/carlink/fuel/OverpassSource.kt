package com.carlink.fuel

import com.carlink.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * The free source: fuel stations from OpenStreetMap (`amenity=fuel` within the radius), through the public Overpass API.
 * No prices. For diesel, a station tagged `fuel:diesel=yes` comes ahead of one whose tags don't say, and one tagged
 * `no` is left out. The card shows "© OpenStreetMap contributors" with them (ODbL). The public instance asks for a
 * client that names itself and asks rarely ([LookupThrottle] keeps it to a few lookups an hour).
 */
class OverpassSource(
    /** POSTs an Overpass QL query and returns the JSON; [IOException] when it can't. Swapped in tests. */
    private val post: (query: String) -> String = ::postToOverpass,
) : FuelPriceSource {
    override val name = "OpenStreetMap"
    override val attribution = "© OpenStreetMap contributors"

    override fun stationsNear(
        here: GeoPoint,
        fuel: CarFuel,
        radiusM: Double,
    ): List<Station> = parse(post(query(here, radiusM)), fuel).nearestFirst(here)

    companion object {
        const val ENDPOINT = "https://overpass-api.de/api/interpreter"
        private const val TIMEOUT_S = 15
        private const val TIMEOUT_MS = 20_000
        private const val MAX_RESULTS = 40

        /** `amenity=fuel` nodes, ways and relations within [radiusM] of [here], with a centre for each and their tags. */
        fun query(
            here: GeoPoint,
            radiusM: Double,
        ): String {
            val around = String.format(Locale.ROOT, "%.0f,%.6f,%.6f", radiusM, here.lat, here.lon)
            return "[out:json][timeout:$TIMEOUT_S];nwr[\"amenity\"=\"fuel\"](around:$around);out center tags $MAX_RESULTS;"
        }

        /** Overpass's JSON to stations: a name from `name`, `brand` or `operator`; an address from `addr:*`. */
        fun parse(
            json: String,
            fuel: CarFuel,
        ): List<Station> {
            val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
            return (0 until elements.length()).mapNotNull { i -> station(elements.getJSONObject(i), fuel) }
        }

        private fun station(
            e: JSONObject,
            fuel: CarFuel,
        ): Station? {
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val centre = e.optJSONObject("center")
            val lat = if (e.has("lat")) e.optDouble("lat") else centre?.optDouble("lat") ?: Double.NaN
            val lon = if (e.has("lon")) e.optDouble("lon") else centre?.optDouble("lon") ?: Double.NaN
            if (lat.isNaN() || lon.isNaN()) return null
            val sells = sells(tags, fuel)
            if (sells == false) return null
            val name = listOf("name", "brand", "operator").firstNotNullOfOrNull { tags.optString(it).ifBlank { null } } ?: "Fuel station"
            return Station("osm:${e.optString("type")}/${e.optLong("id")}", name, address(tags), GeoPoint(lat, lon), sellsFuel = sells)
        }

        /** Diesel only: `fuel:diesel` yes, no, or not said. Every station sells gasoline as far as OSM tags go. */
        private fun sells(
            tags: JSONObject,
            fuel: CarFuel,
        ): Boolean? =
            if (fuel.kind != CarFuel.Kind.DIESEL) {
                null
            } else {
                when (tags.optString("fuel:diesel")) {
                    "yes" -> true
                    "no" -> false
                    else -> null
                }
            }

        private fun address(tags: JSONObject): String? {
            val street = listOf("addr:housenumber", "addr:street").mapNotNull { tags.optString(it).ifBlank { null } }.joinToString(" ")
            val city = tags.optString("addr:city").ifBlank { null }
            return listOfNotNull(street.ifBlank { null }, city).joinToString(", ").ifBlank { null }
        }

        private fun postToOverpass(query: String): String {
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.requestMethod = "POST"
                conn.doOutput = true
                // Overpass's usage policy: a client names itself.
                conn.setRequestProperty("User-Agent", "Carlink/${BuildConfig.VERSION_NAME} (Android Automotive; https://github.com/enigy/carlink)")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write("data=${URLEncoder.encode(query, Charsets.UTF_8.name())}".toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code from Overpass")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
