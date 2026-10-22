package com.carlink.navigation

import androidx.car.app.navigation.model.LaneDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** One lane as the cluster gets it: the direction it leads and whether to be in it. */
data class LaneHint(
    /**
     * Degrees, Apple's convention: 0 straight, +90 right, -90 left, ±180 back. For a lane to be
     * in, the direction the route takes from it.
     */
    val angle: Int,
    val recommended: Boolean,
    /** The lane's other directions (a straight-or-right lane taken right has others = [0]). */
    val others: List<Int> = emptyList(),
)

/** One lane record from the box's `kind=laneGuidance` JSON (iAP2 0x5204 LaneInformation). */
data class LaneRecord(
    val lane: Int,
    /** 0 do not take, 1 usable, 2 preferred. */
    val status: Int,
    /** The lane's first direction (`laneAngle`). */
    val angle: Int,
    /** Every direction the lane leads (`laneAngles`, from a box that sends them); else just [angle]. */
    val angles: List<Int> = listOf(angle),
    /** The direction the route takes from this lane (`laneAngleHighlight`), when sent. */
    val highlight: Int? = null,
)

/**
 * Lane guidance for the cluster/HUD. GM's cluster and HUD run GM's own code. Carlink3 can draw lanes
 * into the turn icon GM already shows ([LaneIconComposer]), and offer Step lanes plus a lane image
 * for GM to place itself. With Android Auto phones GM shows lanes where the turn icon sits, which
 * is what the turn-icon layouts aim for.
 */
object LaneGuidance {
    private const val STRAIGHT_MAX = 10
    private const val SLIGHT_MAX = 45
    private const val NORMAL_MAX = 135
    private const val U_TURN_MIN = 175

    /**
     * Records of one guidance block, in lane order, to hints. "Preferred" lanes win; else "usable".
     * A lane to be in points the way the route goes from it (its highlighted direction), with its
     * other directions kept to draw beside it.
     */
    fun fromRecords(records: Collection<LaneRecord>): List<LaneHint> {
        val ordered = records.sortedBy { it.lane }
        val anyPreferred = ordered.any { it.status == 2 }
        return ordered.map { r ->
            val recommended = if (anyPreferred) r.status == 2 else r.status == 1
            val main = if (recommended) r.highlight ?: r.angle else r.angle
            LaneHint(main, recommended, r.angles.filter { it != main }.distinct())
        }
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
}

/** What lane guidance has seen, for the status line under Settings › Cluster. */
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
