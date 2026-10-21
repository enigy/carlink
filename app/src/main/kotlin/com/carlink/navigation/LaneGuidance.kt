package com.carlink.navigation

import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** One lane as the cluster gets it: the direction it leads and whether to be in it. */
data class LaneHint(
    /** Degrees, Apple's convention: 0 straight, +90 right, -90 left, ±180 back. */
    val angle: Int,
    val recommended: Boolean,
)

/** One lane record from the box's `kind=laneGuidance` JSON (iAP2 0x5204 LaneInformation). */
data class LaneRecord(
    val lane: Int,
    /** 0 do not take, 1 usable, 2 preferred. */
    val status: Int,
    val angle: Int,
)

/**
 * Lane guidance for the cluster/HUD. GM's cluster and HUD run GM's own code. Carlink3 can draw lanes
 * into the turn icon GM already shows ([LaneIconComposer]), and offer Step lanes plus a lane image
 * for GM to place itself. With Android Auto phones GM shows lanes where the turn icon sits, which
 * is what the turn-icon tests aim for.
 */
object LaneGuidance {
    private const val STRAIGHT_MAX = 10
    private const val SLIGHT_MAX = 45
    private const val NORMAL_MAX = 135
    private const val U_TURN_MIN = 175

    /** Records of one guidance block, in lane order, to hints. "Preferred" lanes win; else "usable". */
    fun fromRecords(records: Collection<LaneRecord>): List<LaneHint> {
        val ordered = records.sortedBy { it.lane }
        val anyPreferred = ordered.any { it.status == 2 }
        return ordered.map { r -> LaneHint(r.angle, if (anyPreferred) r.status == 2 else r.status == 1) }
    }

    /** The androidx [LaneDirection] shape for an Apple lane angle. */
    fun shapeFor(angle: Int): Int {
        val a = abs(angle)
        val right = angle > 0
        return when {
            a <= STRAIGHT_MAX -> LaneDirection.SHAPE_STRAIGHT
            a <= SLIGHT_MAX -> if (right) LaneDirection.SHAPE_SLIGHT_RIGHT else LaneDirection.SHAPE_SLIGHT_LEFT
            a <= NORMAL_MAX -> if (right) LaneDirection.SHAPE_NORMAL_RIGHT else LaneDirection.SHAPE_NORMAL_LEFT
            a < U_TURN_MIN -> if (right) LaneDirection.SHAPE_SHARP_RIGHT else LaneDirection.SHAPE_SHARP_LEFT
            else -> if (right) LaneDirection.SHAPE_U_TURN_RIGHT else LaneDirection.SHAPE_U_TURN_LEFT
        }
    }

    private const val MIN_TEST_LANES = 2
    private const val TEST_LANE_COUNTS = 5 // 2..6 lanes
    private const val SIDE_LANE_MIN = 5 // from five lanes the outer lane turns the other way
    private const val SECOND_TURN_LANE_MIN = 4 // from four lanes two lanes make the turn
    private const val SLIGHT = 45
    private const val NORMAL = 90
    private const val U_TURN = 180

    /**
     * Settings › Cluster › Lane source › Test pattern: lanes for a step whether or not the phone sent
     * any, so a parked check shows them on every turn of any route. [step] picks 2 to 6 lanes and
     * stays the same for one step; the recommended lanes sit on the side [maneuverType] turns to.
     */
    fun testPattern(
        maneuverType: Int,
        step: Int,
    ): List<LaneHint> {
        val count = MIN_TEST_LANES + Math.floorMod(step, TEST_LANE_COUNTS)
        val turn = turnOf(maneuverType)
        if (turn == 0) return straightPattern(count)
        // Built as if turning right (lanes left to right), then mirrored for a left turn.
        val angle = turnAngle(maneuverType)
        val right =
            List(count) { i ->
                val fromRight = count - 1 - i
                when {
                    fromRight == 0 -> LaneHint(angle, true)
                    fromRight == 1 && count >= SECOND_TURN_LANE_MIN -> LaneHint(angle, true)
                    i == 0 && count >= SIDE_LANE_MIN -> LaneHint(-NORMAL, false)
                    else -> LaneHint(0, false)
                }
            }
        return if (turn > 0) right else right.reversed().map { LaneHint(-it.angle, it.recommended) }
    }

    /** Straight on: the middle lanes; with five or more, the outer two are turn lanes. */
    private fun straightPattern(count: Int): List<LaneHint> =
        List(count) { i ->
            val outer = i == 0 || i == count - 1
            when {
                count >= SIDE_LANE_MIN && i == 0 -> LaneHint(-NORMAL, false)
                count >= SIDE_LANE_MIN && i == count - 1 -> LaneHint(NORMAL, false)
                else -> LaneHint(0, count < 3 || !outer)
            }
        }

    /** -1 when [maneuverType] (androidx) bears left, +1 right, 0 straight or unknown. */
    fun turnOf(maneuverType: Int): Int =
        when (maneuverType) {
            in LEFT_TYPES -> -1
            in RIGHT_TYPES -> 1
            else -> 0
        }

    private fun turnAngle(maneuverType: Int): Int =
        when (maneuverType) {
            in SLIGHT_TYPES -> SLIGHT
            Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_U_TURN_RIGHT, Maneuver.TYPE_ON_RAMP_U_TURN_LEFT, Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT -> U_TURN
            else -> NORMAL
        }

    private val LEFT_TYPES =
        setOf(
            Maneuver.TYPE_KEEP_LEFT,
            Maneuver.TYPE_TURN_SLIGHT_LEFT,
            Maneuver.TYPE_TURN_NORMAL_LEFT,
            Maneuver.TYPE_TURN_SHARP_LEFT,
            Maneuver.TYPE_U_TURN_LEFT,
            Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT,
            Maneuver.TYPE_ON_RAMP_NORMAL_LEFT,
            Maneuver.TYPE_ON_RAMP_SHARP_LEFT,
            Maneuver.TYPE_ON_RAMP_U_TURN_LEFT,
            Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT,
            Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT,
            Maneuver.TYPE_FORK_LEFT,
            Maneuver.TYPE_MERGE_LEFT,
        )

    private val RIGHT_TYPES =
        setOf(
            Maneuver.TYPE_KEEP_RIGHT,
            Maneuver.TYPE_TURN_SLIGHT_RIGHT,
            Maneuver.TYPE_TURN_NORMAL_RIGHT,
            Maneuver.TYPE_TURN_SHARP_RIGHT,
            Maneuver.TYPE_U_TURN_RIGHT,
            Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT,
            Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT,
            Maneuver.TYPE_ON_RAMP_SHARP_RIGHT,
            Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT,
            Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT,
            Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
            Maneuver.TYPE_FORK_RIGHT,
            Maneuver.TYPE_MERGE_RIGHT,
        )

    private val SLIGHT_TYPES =
        setOf(
            Maneuver.TYPE_KEEP_LEFT,
            Maneuver.TYPE_KEEP_RIGHT,
            Maneuver.TYPE_TURN_SLIGHT_LEFT,
            Maneuver.TYPE_TURN_SLIGHT_RIGHT,
            Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT,
            Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT,
            Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT,
            Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT,
            Maneuver.TYPE_FORK_LEFT,
            Maneuver.TYPE_FORK_RIGHT,
            Maneuver.TYPE_MERGE_LEFT,
            Maneuver.TYPE_MERGE_RIGHT,
        )
}

/** What the lane tests have seen, for the status line under Settings › Cluster. */
object LaneActivity {
    /** The last lanes the iPhone sent: how many, how many to be in, and when (wall clock). */
    data class Seen(
        val lanes: Int,
        val recommended: Int,
        val atMs: Long,
    )

    private val seenState = MutableStateFlow<Seen?>(null)
    val seen: StateFlow<Seen?> = seenState.asStateFlow()

    private val askedState = MutableStateFlow(false)

    /** Whether the config pushed for the live adapter connection asked the iPhone for lanes. */
    val askedThisConnection: StateFlow<Boolean> = askedState.asStateFlow()

    fun noteLanes(lanes: List<LaneHint>) {
        if (lanes.isEmpty()) return
        seenState.value = Seen(lanes.size, lanes.count { it.recommended }, System.currentTimeMillis())
    }

    fun noteAsked(asked: Boolean) {
        askedState.value = asked
    }
}
