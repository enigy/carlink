package com.carlink.fuel

import com.carlink.ui.settings.FuelGrade
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Places API (New) Nearby Search with the driver's key (the brief of 11 Nov, stage C). Places and prices made up. */
class GooglePlacesTest {
    private val here = GeoPoint(-33.9, 151.2)
    private val diesel = CarFuel(CarFuel.Kind.DIESEL)
    private val regular = CarFuel(CarFuel.Kind.GASOLINE, FuelGrade.REGULAR)

    private val answer =
        """
        {"places":[
          {"id":"p1","displayName":{"text":"Made-up Gas"},"formattedAddress":"1 Example Road","location":{"latitude":-33.91,"longitude":151.2},
           "fuelOptions":{"fuelPrices":[
             {"type":"REGULAR_UNLEADED","price":{"currencyCode":"USD","units":"3","nanos":459000000},"updateTime":"2026-11-11T08:00:00Z"},
             {"type":"DIESEL","price":{"currencyCode":"USD","units":"3","nanos":899000000},"updateTime":"2026-11-10T08:00:00.123Z"}]}},
          {"id":"p2","displayName":{"text":"No Prices Fuel"},"location":{"latitude":-33.92,"longitude":151.2}},
          {"id":"p3","displayName":{"text":"Nowhere"}}
        ]}
        """.trimIndent()

    @Test
    fun `the request asks for gas stations in the circle, nearest first`() {
        val body = JSONObject(GooglePlaces.body(here, 8_046.72))
        assertEquals("gas_station", body.getJSONArray("includedTypes").getString(0))
        assertEquals("DISTANCE", body.getString("rankPreference"))
        assertEquals(10, body.getInt("maxResultCount"))
        val circle = body.getJSONObject("locationRestriction").getJSONObject("circle")
        assertEquals(-33.9, circle.getJSONObject("center").getDouble("latitude"), 1e-9)
        assertEquals(8_046.72, circle.getDouble("radius"), 1e-9)
        assertEquals("Google's cap", 50_000.0, JSONObject(GooglePlaces.body(here, 99_000.0)).getJSONObject("locationRestriction").getJSONObject("circle").getDouble("radius"), 0.0)
        assertEquals("places.id,places.displayName,places.formattedAddress,places.location,places.fuelOptions", GooglePlaces.FIELD_MASK)
    }

    @Test
    fun `each station has its price for the car's fuel, with its age, or none`() {
        val d = GooglePlaces.parse(answer, diesel)
        assertEquals(listOf("google:p1", "google:p2"), d.map { it.id })
        assertEquals(3.899, d[0].price!!, 1e-9)
        assertEquals("USD", d[0].currency)
        assertEquals(Instant.parse("2026-11-10T08:00:00.123Z").toEpochMilli(), d[0].priceAtMs)
        assertEquals("1 Example Road", d[0].address)
        assertNull("no price for this fuel", d[1].price)
        assertNull(d[1].address)
        assertEquals(3.459, GooglePlaces.parse(answer, regular)[0].price!!, 1e-9)
        assertEquals("no fuel type, no price", null, GooglePlaces.parse(answer, CarFuel(CarFuel.Kind.ELECTRIC))[0].price)
    }

    @Test
    fun `Google's refusals in the driver's words`() {
        fun err(
            code: Int,
            reason: String,
            message: String = "",
        ) = """{"error":{"code":$code,"message":"$message","details":[{"reason":"$reason"}]}}"""
        assertTrue(GooglePlaces.problem(400, err(400, "API_KEY_INVALID")).contains("isn't valid"))
        assertTrue(GooglePlaces.problem(403, err(403, "SERVICE_DISABLED")).contains("isn't turned on"))
        assertTrue(GooglePlaces.problem(403, err(403, "BILLING_DISABLED")).contains("Billing"))
        assertTrue(GooglePlaces.problem(403, err(403, "API_KEY_SERVICE_BLOCKED")).contains("restrictions"))
        assertTrue(GooglePlaces.problem(403, err(403, "API_KEY_ANDROID_APP_BLOCKED")).contains("restrictions"))
        assertEquals("Google refused the key (HTTP 403).", GooglePlaces.problem(403, "not json"))
        assertEquals("Google answered HTTP 404.", GooglePlaces.problem(404, null))
    }

    @Test
    fun `the source sends the key, the field mask and the app, and judges the answer`() {
        var sent: Map<String, String> = emptyMap()
        val ok =
            GooglePlacesSource(key = { "AIzaMadeUpKey" }, appHeaders = mapOf("X-Android-Package" to "com.example")) { _, headers ->
                sent = headers
                HttpAnswer(200, answer)
            }
        assertEquals(2, ok.stationsNear(here, diesel, 8_000.0).size)
        assertEquals("AIzaMadeUpKey", sent["X-Goog-Api-Key"])
        assertEquals(GooglePlaces.FIELD_MASK, sent["X-Goog-FieldMask"])
        assertEquals("com.example", sent["X-Android-Package"])
        assertTrue(GooglePlacesSource.failure(HttpAnswer(429, "")) is SourceBusy)
        assertTrue(GooglePlacesSource.failure(HttpAnswer(503, "")) is SourceBusy)
        assertTrue(GooglePlacesSource.failure(HttpAnswer(403, "")) is GoogleKeyProblem)
        assertThrows(GoogleKeyProblem::class.java) { GooglePlacesSource(key = { null }) { _, _ -> HttpAnswer(200, answer) }.stationsNear(here, diesel, 8_000.0) }
    }

    @Test
    fun `the monthly cap counts this month and starts over in the next`() {
        var b = MonthlyBudget("2026-11", 0, cap = 2)
        assertTrue(b.allows("2026-11"))
        b = b.spend("2026-11").spend("2026-11")
        assertEquals(2, b.used("2026-11"))
        assertEquals("at the cap", false, b.allows("2026-11"))
        assertEquals("a new month", 0, b.used("2026-12"))
        assertTrue(b.allows("2026-12"))
        assertEquals(MonthlyBudget("2026-12", 1, 2), b.spend("2026-12"))
        assertEquals(900, MonthlyBudget.DEFAULT_CAP)
    }
}
