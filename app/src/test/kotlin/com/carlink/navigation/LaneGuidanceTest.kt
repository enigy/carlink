package com.carlink.navigation

import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lane records from the box -> the lanes the cluster step carries. */
@RunWith(RobolectricTestRunner::class)
class LaneGuidanceTest {
    @Before
    fun reset() {
        OcbmNavigationFeed.clear()
    }

    @After
    fun tearDown() = reset()

    @Test
    fun anglesMapToShapes() {
        assertEquals(LaneDirection.SHAPE_STRAIGHT, LaneGuidance.shapeFor(0))
        assertEquals(LaneDirection.SHAPE_SLIGHT_RIGHT, LaneGuidance.shapeFor(30))
        assertEquals(LaneDirection.SHAPE_NORMAL_LEFT, LaneGuidance.shapeFor(-90))
        assertEquals(LaneDirection.SHAPE_SHARP_RIGHT, LaneGuidance.shapeFor(150))
        assertEquals(LaneDirection.SHAPE_U_TURN_LEFT, LaneGuidance.shapeFor(-180))
    }

    @Test
    fun preferredLanesWinOverUsableOnes() {
        val lanes = LaneGuidance.fromRecords(listOf(LaneRecord(1, 1, 0), LaneRecord(0, 2, -45), LaneRecord(2, 0, 90)))
        assertEquals(listOf(LaneHint(-45, true), LaneHint(0, false), LaneHint(90, false)), lanes)
        val usable = LaneGuidance.fromRecords(listOf(LaneRecord(0, 1, 0), LaneRecord(1, 0, 90)))
        assertEquals(listOf(true, false), usable.map { it.recommended })
    }

    private fun maneuver(
        index: Int,
        linked: Int? = null,
    ) = JSONObject().apply {
        put("kind", "maneuver")
        put("index", index)
        put("maneuverType", 8)
        put("description", "Exit $index")
        if (linked != null) put("linkedLaneGuidance", linked)
    }

    @Suppress("LongParameterList") // one field per JSON key the box sends
    private fun lane(
        block: Int,
        index: Int,
        status: Int,
        angle: Int,
        count: Int? = null,
        angles: List<Int>? = null,
        highlight: Int? = null,
    ) = JSONObject().apply {
        put("kind", "laneGuidance")
        put("guidanceIndex", block)
        put("index", index)
        put("laneStatus", status)
        put("laneAngle", angle)
        if (count != null) put("laneCount", count)
        if (angles != null) put("laneAngles", JSONArray(angles))
        if (highlight != null) put("laneAngleHighlight", highlight)
    }

    private fun guidance(
        cur: Int,
        showLanes: Boolean? = null,
        laneBlock: Int? = null,
    ) = JSONObject().apply {
        put("kind", "routeGuidance")
        put("routeGuidanceState", 1)
        put("currentManeuverIndex", cur)
        if (showLanes != null) put("presentLaneGuidance", if (showLanes) 1 else 0)
        if (laneBlock != null) put("laneGuidanceCurrentIndex", laneBlock)
    }

    private val lanesNow get() = NavigationStateManager.state.value.lanes

    @Test
    fun theIphoneSaysWhichBlockToShowAndWhen() {
        OcbmNavigationFeed.onManeuver(maneuver(1))
        OcbmNavigationFeed.onRouteGuidance(guidance(1, showLanes = false, laneBlock = 0))
        // The whole route's blocks arrive at once, as on the 2026-10-21 drive.
        for (b in 0..3) {
            OcbmNavigationFeed.onLaneGuidance(lane(b, 0, 0, 0))
            OcbmNavigationFeed.onLaneGuidance(lane(b, 1, 2, 90))
        }
        assertTrue("not showing yet: no lanes, and no guess at the newest block", lanesNow.isEmpty())
        OcbmNavigationFeed.onRouteGuidance(guidance(1, showLanes = true, laneBlock = 2))
        assertEquals(listOf(LaneHint(0, false), LaneHint(90, true)), lanesNow)
        OcbmNavigationFeed.onRouteGuidance(guidance(1, showLanes = false, laneBlock = 2))
        assertTrue(lanesNow.isEmpty())
    }

    @Test
    fun withoutALinkOrCurrentIndexNothingIsGuessed() {
        OcbmNavigationFeed.onManeuver(maneuver(1))
        OcbmNavigationFeed.onRouteGuidance(guidance(1))
        OcbmNavigationFeed.onLaneGuidance(lane(13, 0, 2, 0))
        assertTrue(lanesNow.isEmpty())
    }

    @Test
    fun aReSentBlockDropsTheLanesOfTheOldRoute() {
        OcbmNavigationFeed.onManeuver(maneuver(1, linked = 0))
        OcbmNavigationFeed.onRouteGuidance(guidance(1))
        for (i in 0..4) OcbmNavigationFeed.onLaneGuidance(lane(0, i, 0, 0))
        assertEquals(5, lanesNow.size)
        OcbmNavigationFeed.onLaneGuidance(lane(0, 0, 2, 0))
        OcbmNavigationFeed.onLaneGuidance(lane(0, 1, 0, 90))
        assertEquals(listOf(LaneHint(0, true), LaneHint(90, false)), lanesNow)
    }

    @Test
    fun laneCountHoldsTheBlockUntilItIsComplete() {
        OcbmNavigationFeed.onManeuver(maneuver(1))
        OcbmNavigationFeed.onRouteGuidance(guidance(1, showLanes = true, laneBlock = 4))
        OcbmNavigationFeed.onLaneGuidance(lane(4, 0, 0, 0, count = 3))
        OcbmNavigationFeed.onLaneGuidance(lane(4, 1, 2, 0, count = 3))
        assertTrue("2 of 3 lanes: not published yet", lanesNow.isEmpty())
        OcbmNavigationFeed.onLaneGuidance(lane(4, 2, 0, 90, count = 3))
        assertEquals(3, lanesNow.size)
    }

    @Test
    fun aLaneToBeInPointsTheWayTheRouteGoes() {
        OcbmNavigationFeed.onManeuver(maneuver(1))
        OcbmNavigationFeed.onRouteGuidance(guidance(1, showLanes = true, laneBlock = 0))
        OcbmNavigationFeed.onLaneGuidance(lane(0, 0, 0, 0, count = 2))
        OcbmNavigationFeed.onLaneGuidance(lane(0, 1, 2, 0, count = 2, angles = listOf(0, 90), highlight = 90))
        assertEquals(listOf(LaneHint(0, false), LaneHint(90, true, others = listOf(0))), lanesNow)
    }

    @Test
    fun linkedBlockReachesTheCurrentStep() {
        OcbmNavigationFeed.onManeuver(maneuver(4, linked = 7))
        OcbmNavigationFeed.onRouteGuidance(guidance(4))
        OcbmNavigationFeed.onLaneGuidance(lane(7, 0, 0, 0))
        OcbmNavigationFeed.onLaneGuidance(lane(7, 1, 2, 45))
        assertEquals(listOf(LaneHint(0, false), LaneHint(45, true)), NavigationStateManager.state.value.lanes)
    }

    @Test
    fun noLanesUntilThePhoneSendsSome() {
        OcbmNavigationFeed.onManeuver(maneuver(1))
        OcbmNavigationFeed.onRouteGuidance(guidance(1))
        assertTrue(lanesNow.isEmpty())
    }

    @Test
    fun receivedLanesReachTheStatusLine() {
        OcbmNavigationFeed.onManeuver(maneuver(2, linked = 3))
        OcbmNavigationFeed.onRouteGuidance(guidance(2))
        OcbmNavigationFeed.onLaneGuidance(lane(3, 0, 1, 0))
        OcbmNavigationFeed.onLaneGuidance(lane(3, 1, 2, 90))
        val seen = LaneActivity.seen.value!!
        assertEquals(2, seen.lanes)
        assertEquals(1, seen.recommended)
    }

    private fun hints(vararg lanes: Pair<Int, Boolean>) = lanes.map { LaneHint(it.first, it.second) }

    @Test
    fun testPatternLightsTheLanesOnTheTurnSide() {
        assertEquals(hints(0 to false, 90 to true), LaneGuidance.testPattern(Maneuver.TYPE_TURN_NORMAL_RIGHT, step = 0))
        assertEquals(hints(0 to false, 0 to false, 90 to true, 90 to true), LaneGuidance.testPattern(Maneuver.TYPE_TURN_NORMAL_RIGHT, step = 2))
        assertEquals(
            hints(-90 to true, -90 to true, 0 to false, 0 to false, 90 to false),
            LaneGuidance.testPattern(Maneuver.TYPE_TURN_NORMAL_LEFT, step = 3),
        )
        assertEquals(hints(0 to false, 45 to true), LaneGuidance.testPattern(Maneuver.TYPE_KEEP_RIGHT, step = 5))
    }

    @Test
    fun testPatternGoingStraightLightsTheMiddle() {
        assertEquals(
            hints(-90 to false, 0 to true, 0 to true, 0 to true, 0 to true, 90 to false),
            LaneGuidance.testPattern(Maneuver.TYPE_STRAIGHT, step = 4),
        )
        assertEquals(hints(0 to false, 0 to true, 0 to false), LaneGuidance.testPattern(Maneuver.TYPE_STRAIGHT, step = 1))
    }

    @Test
    fun testPatternAlwaysHasTwoToSixLanes() {
        for (step in -12..12) {
            val n = LaneGuidance.testPattern(Maneuver.TYPE_TURN_SHARP_LEFT, step).size
            assertTrue("step $step gave $n lanes", n in 2..6)
        }
    }
}
