package com.carlink.navigation

import androidx.car.app.navigation.model.LaneDirection
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
        // GM's templates host crashed on U-turn lane shapes (2026-10-22): sent as sharp turns.
        assertEquals(LaneDirection.SHAPE_SHARP_LEFT, LaneGuidance.shapeFor(-180))
        assertEquals(LaneDirection.SHAPE_SHARP_RIGHT, LaneGuidance.shapeFor(180))
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
    fun aLoneZeroBesideATurnIsAPlaceholder() {
        // As the iPhone sends preferred lanes: laneAngles=[0], the real direction in the highlight.
        val turnOnly = LaneGuidance.fromRecords(listOf(LaneRecord(0, 0, 0), LaneRecord(1, 2, 0, angles = listOf(0), highlight = 90)))
        assertEquals(listOf(LaneHint(0, false), LaneHint(90, true)), turnOnly)
        // A 0 highlight with one turn listed: straight on, the turn beside it.
        val uTurn = LaneGuidance.fromRecords(listOf(LaneRecord(0, 2, -180, angles = listOf(-180), highlight = 0)))
        assertEquals(listOf(LaneHint(0, true, others = listOf(-180))), uTurn)
        // A real second direction stays: a left lane that also allows the U-turn being taken.
        val leftOrU = LaneGuidance.fromRecords(listOf(LaneRecord(0, 2, -90, angles = listOf(-90), highlight = -180)))
        assertEquals(listOf(LaneHint(-180, true, others = listOf(-90))), leftOrU)
        // Straight ahead in a preferred lane is still straight ahead.
        val straight = LaneGuidance.fromRecords(listOf(LaneRecord(0, 2, 0, angles = listOf(0), highlight = 0)))
        assertEquals(listOf(LaneHint(0, true)), straight)
    }

    @Test
    fun aZeroHighlightOnATurnLaneIsStraightOnNotTheTurn() {
        // The 2026-10-29 drive, records as received. Approaching I-575 exit 7 southbound (block 1, 16:46:47):
        // stay in the left two lanes; the cluster showed lane 1 as a highlighted slight right.
        val exit7 =
            LaneGuidance.fromRecords(
                listOf(
                    LaneRecord(0, 2, 0, angles = listOf(0), highlight = 0),
                    LaneRecord(1, 2, 45, angles = listOf(45), highlight = 0),
                    LaneRecord(2, 0, 45, angles = listOf(45)),
                ),
            )
        assertEquals(listOf(LaneHint(0, true), LaneHint(0, true, others = listOf(45)), LaneHint(45, false)), exit7)
        // The intersection before the right turn onto Jiles Rd (block 0, 17:52:15): keep right, straight on.
        // The cluster showed a highlighted right turn; the turn itself came as the next block.
        val beforeJiles = LaneGuidance.fromRecords(listOf(LaneRecord(0, 0, 0, angles = listOf(0)), LaneRecord(1, 2, 90, angles = listOf(90), highlight = 0)))
        assertEquals(listOf(LaneHint(0, false), LaneHint(0, true, others = listOf(90))), beforeJiles)
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
}
