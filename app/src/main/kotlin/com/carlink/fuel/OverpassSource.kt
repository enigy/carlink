package com.carlink.fuel

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * The free source: fuel stations from OpenStreetMap (`amenity=fuel` within the radius), through the public Overpass API.
 * No prices. Each station keeps its `fuel:diesel` tag, so for diesel the card puts one tagged `yes` ahead of one whose
 * tags don't say and leaves out one tagged `no` ([StationList]). The card shows "© OpenStreetMap contributors" with them
 * (ODbL). The public instance asks for a client that names itself and asks rarely ([LookupThrottle] keeps it to a few
 * lookups an hour), and it can be slow or at capacity: a busy answer or a read timeout is [SourceBusy], tried again
 * later; no connection is [SourceUnreachable]. Every station in the radius comes back (a count in the query would keep
 * the first by id, not the nearest): the nearest [KEEP] are kept, and up to [KEEP] more tagged for diesel, enough for
 * either fuel's list.
 */
class OverpassSource(
    /** The instances to ask, in turn: after a busy answer the next lookup goes to the next one. */
    private val endpoints: List<String> = ENDPOINTS,
    /** POSTs an Overpass QL query to an endpoint and returns the JSON; [IOException] when it can't. Swapped in tests. */
    private val post: (endpoint: String, query: String) -> String = ::postTo,
) : FuelPriceSource {
    override val name = "OpenStreetMap"
    override val attribution = "© OpenStreetMap contributors"

    @Volatile private var next = 0

    @Volatile private var host: String? = null

    /** The instance asked last, host only: the log tells which one answered, was busy or failed. */
    override val detail: String? get() = host

    override fun stationsNear(
        here: GeoPoint,
        radiusM: Double,
    ): List<Station> {
        val endpoint = endpoints[next % endpoints.size]
        host = URI(endpoint).host
        val json =
            try {
                post(endpoint, query(here, radiusM))
            } catch (e: SourceBusy) {
                next = (next + 1) % endpoints.size
                throw e
            }
        val near = parse(json).nearestFirst(here)
        return near.take(KEEP) + near.drop(KEEP).filter { it.diesel == true }.take(KEEP)
    }

    companion object {
        /**
         * The public instances, in turn (the OSM wiki's table, 11 Nov): FOSSGIS's main overpass-api.de, which an app's users
         * share (under 10,000 queries a day together) and which the wiki calls overloaded; then Private.coffee's, "free to use
         * in any project, no rate limit", which asks for notice before large-scale use. After a busy answer the next lookup
         * asks the other.
         */
        val ENDPOINTS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter")

        /** Overpass's own limit for the query, under the read timeout, so the server gives up first. */
        private const val QUERY_TIMEOUT_S = 50
        private const val READ_TIMEOUT_MS = 60_000
        const val KEEP = 40

        /** `amenity=fuel` nodes, ways and relations within [radiusM] of [here], with a centre for each and their tags. */
        fun query(
            here: GeoPoint,
            radiusM: Double,
        ): String {
            val around = String.format(Locale.ROOT, "%.0f,%.6f,%.6f", radiusM, here.lat, here.lon)
            return "[out:json][timeout:$QUERY_TIMEOUT_S];nwr[\"amenity\"=\"fuel\"](around:$around);out center tags;"
        }

        /** Overpass's JSON to stations: a name from `name`, `brand` or `operator`; an address from `addr:*`; the diesel tag. */
        fun parse(json: String): List<Station> {
            val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
            return (0 until elements.length()).mapNotNull { i -> station(elements.getJSONObject(i)) }
        }

        private fun station(e: JSONObject): Station? {
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val centre = e.optJSONObject("center")
            val lat = if (e.has("lat")) e.optDouble("lat") else centre?.optDouble("lat") ?: Double.NaN
            val lon = if (e.has("lon")) e.optDouble("lon") else centre?.optDouble("lon") ?: Double.NaN
            if (lat.isNaN() || lon.isNaN()) return null
            val name = listOf("name", "brand", "operator").firstNotNullOfOrNull { tags.optString(it).ifBlank { null } } ?: "Fuel station"
            return Station("osm:${e.optString("type")}/${e.optLong("id")}", name, address(tags), GeoPoint(lat, lon), diesel = diesel(tags))
        }

        /** `fuel:diesel`: yes, no, or not said. Every station sells gasoline as far as OSM tags go. */
        private fun diesel(tags: JSONObject): Boolean? =
            when (tags.optString("fuel:diesel")) {
                "yes" -> true
                "no" -> false
                else -> null
            }

        private fun address(tags: JSONObject): String? {
            val street = listOf("addr:housenumber", "addr:street").mapNotNull { tags.optString(it).ifBlank { null } }.joinToString(" ")
            val city = tags.optString("addr:city").ifBlank { null }
            return listOfNotNull(street.ifBlank { null }, city).joinToString(", ").ifBlank { null }
        }

        /** The query to [endpoint]: busy for 429, 5xx or a read timeout ([httpFailure]), the JSON for 200. */
        private fun postTo(
            endpoint: String,
            query: String,
        ): String {
            val body = "data=${URLEncoder.encode(query, Charsets.UTF_8.name())}".toByteArray(Charsets.UTF_8)
            // Http names Carlink in the User-Agent, as Overpass's policy asks (a default curl name drew 406 on the bench).
            val answer = Http.post(endpoint, mapOf("Content-Type" to "application/x-www-form-urlencoded"), body, READ_TIMEOUT_MS)
            if (answer.code != HttpURLConnection.HTTP_OK) throw httpFailure(answer.code, answer.retryAfter)
            return answer.body
        }
    }
}
