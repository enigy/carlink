package com.carlink.fuel

import kotlin.math.cos

/**
 * Debug builds only: five made-up stations around the head unit, with made-up prices of every age, so the card can be
 * benched without asking Google (the brief of 11 Nov). Nothing leaves the head unit. Names and prices are invented.
 */
class DemoSource(
    private val nowMs: () -> Long = System::currentTimeMillis,
) : FuelPriceSource {
    override val name = "Demo"
    override val attribution = "Demo stations, made up"

    override fun stationsNear(
        here: GeoPoint,
        radiusM: Double,
    ): List<Station> {
        val perDegreeLon = METRES_PER_DEGREE * cos(Math.toRadians(here.lat)).coerceAtLeast(MIN_COS)
        val stations =
            SPOTS.mapIndexed { i, spot ->
                val at = GeoPoint(here.lat + spot.northM / METRES_PER_DEGREE, here.lon + spot.eastM / perDegreeLon)
                val atMs = nowMs() - spot.ageMin * MS_PER_MIN
                Station(
                    id = "demo:$i",
                    name = spot.name,
                    address = "${(i + 1) * 100} ${spot.street}",
                    at = at,
                    street = spot.street,
                    // Every fuel a made-up step from the regular price, so a change of grade shows on the card.
                    prices = spot.price?.let { p -> STEPS.mapValues { (_, step) -> FuelPrice(p + step, "USD", atMs) } }.orEmpty(),
                    diesel = true,
                )
            }
        return stations.filter { Geo.metres(here, it.at) <= radiusM }.nearestFirst(here)
    }

    private class Spot(
        val name: String,
        val street: String,
        val northM: Double,
        val eastM: Double,
        val price: Double?,
        val ageMin: Long,
    )

    companion object {
        private const val METRES_PER_DEGREE = 111_320.0
        private const val MIN_COS = 0.01
        private const val MS_PER_MIN = 60_000L
        private val STEPS = mapOf("REGULAR_UNLEADED" to 0.0, "MIDGRADE" to 0.4, "PREMIUM" to 0.8, "DIESEL" to 0.6, "E85" to -0.7)

        // Made up: a mix of near and far, cheap and dear, fresh and a day stale, and one with no price.
        private val SPOTS =
            listOf(
                Spot("Demo Fuel North", "Hwy 123", 900.0, 100.0, 3.459, 12),
                Spot("Demo Truck Stop", "Industrial Way", -2_400.0, 1_800.0, 3.279, 95),
                Spot("Demo Corner Gas", "Main St", 300.0, -3_600.0, 3.619, 1_560),
                Spot("Demo Highway Fuel", "Hwy 123", 6_200.0, 4_100.0, 3.199, 240),
                Spot("Demo Express", "Oak Ave", -5_500.0, -4_800.0, null, 0),
            )
    }
}
