package com.carlink.navigation

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The OCBM box's route-guidance JSON (`iap2-core/src/metadata.rs`) → the [NavigationState] the
 * cluster/HUD pipeline renders. Shapes here mirror what `route_guidance()` and `maneuver()` emit.
 */
@RunWith(RobolectricTestRunner::class)
class OcbmNavigationFeedTest {
    @Before
    fun reset() {
        OcbmNavigationFeed.clear()
    }

    @After
    fun tearDown() {
        OcbmNavigationFeed.clear()
    }

    private fun maneuver(
        index: Int,
        type: Int,
        description: String,
        road: String = "",
        exit: String? = null,
    ) = JSONObject().apply {
        put("kind", "maneuver")
        put("index", index)
        put("maneuverType", type)
        put("description", description)
        put("afterManeuverRoadName", road)
        put("distanceM", 1200)
        if (exit != null) put("exitInfo", exit)
    }

    private fun guidance(
        current: Int,
        state: Int = 1,
        toManeuver: Int = 800,
        remaining: Int = 42_000,
        destination: String = "Home",
    ) = JSONObject().apply {
        put("kind", "routeGuidance")
        put("routeGuidanceState", state)
        put("currentManeuverIndex", current)
        put("distanceToManeuverM", toManeuver)
        put("distanceRemainingM", remaining)
        put("timeRemainingSec", 1800)
        put("destinationName", destination)
    }

    @Test
    fun liveManeuverAndLookAheadComeFromTheIndex0x5201Names() {
        OcbmNavigationFeed.onManeuver(maneuver(12, 2, "Turn right onto Main St", "Main St"))
        OcbmNavigationFeed.onManeuver(maneuver(13, 8, "Wade Green Rd", "Wade Green Rd", exit = "273"))
        OcbmNavigationFeed.onManeuver(maneuver(14, 9, "I-75 N"))
        OcbmNavigationFeed.onRouteGuidance(guidance(current = 13))

        val s = NavigationStateManager.state.value
        assertEquals(1, s.status)
        assertEquals(8, s.maneuverType)
        assertEquals("Wade Green Rd", s.roadName)
        assertEquals("273", s.exitInfo)
        assertEquals("Exit 273 · Wade Green Rd", s.stepCue)
        assertEquals(800, s.remainDistance)
        assertEquals(42_000, s.distanceToDestination)
        assertEquals(1800, s.timeToDestination)
        assertEquals("Home", s.destinationName)
        assertEquals(9, s.nextManeuverType)
        assertEquals("I-75 N", s.nextRoadName)
        assertNull(s.nextExitInfo)
    }

    @Test
    fun maneuversArrivingAfterTheSummaryStillPublishWhenTheyAreLive() {
        OcbmNavigationFeed.onRouteGuidance(guidance(current = 3))
        assertEquals(0, NavigationStateManager.state.value.maneuverType) // nothing to show yet
        OcbmNavigationFeed.onManeuver(maneuver(3, 1, "Turn left onto Elm St", "Elm St"))
        val s = NavigationStateManager.state.value
        assertEquals(1, s.maneuverType)
        assertEquals("Turn left onto Elm St", s.roadName)
    }

    @Test
    fun junctionGeometryFromTheBoxReachesTheManeuver() {
        val m = maneuver(5, 30, "At the roundabout, take the third exit", "Elm St")
        m.put("junctionAngles", org.json.JSONArray(listOf(-90, 0, 95, 180)))
        m.put("junctionExitAngle", 95)
        OcbmNavigationFeed.onManeuver(m)
        OcbmNavigationFeed.onRouteGuidance(guidance(current = 5))
        val s = NavigationStateManager.state.value
        assertEquals(30, s.maneuverType)
        assertEquals(95, s.exitAngle)
    }

    @Test
    fun endedRouteClearsEverything() {
        OcbmNavigationFeed.onManeuver(maneuver(0, 3, "Head north"))
        OcbmNavigationFeed.onRouteGuidance(guidance(current = 0))
        assertEquals(3, NavigationStateManager.state.value.maneuverType)
        OcbmNavigationFeed.onRouteGuidance(guidance(current = 0, state = 0))
        val s = NavigationStateManager.state.value
        assertEquals(0, s.status)
        assertEquals(0, s.maneuverType)
        assertNull(s.roadName)
    }
}
