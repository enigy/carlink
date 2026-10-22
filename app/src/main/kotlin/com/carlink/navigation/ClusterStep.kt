package com.carlink.navigation

import android.content.Context
import android.os.SystemClock
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import com.carlink.logging.Logger
import com.carlink.logging.logWarn
import com.carlink.ui.settings.LaneSource
import com.carlink.ui.settings.LaneTestStore

/**
 * The step being approached, as both cluster paths send it: the Trip ([TripBuilder]) and the
 * cluster screen's NavigationTemplate ([com.carlink.cluster.CarlinkClusterSession]).
 *
 * The lane guidance tests (Settings › Cluster) act here, all off by default: lanes drawn into the
 * turn icon ([LaneIconComposer]), Step lanes plus a lane image for GM to place itself, the test
 * pattern in place of the iPhone's lanes, and the turn icon and lane strip calibration cards
 * ([LaneSlotCard]). With every test off this is exactly the step Carlink3 always sent.
 */
object ClusterStep {
    /** Mixes the maneuver type with the road name, so each step keeps one test-pattern lane count. */
    private const val STEP_HASH = 31

    fun primary(
        state: NavigationState,
        context: Context,
    ): Step {
        // Turn alert (Settings › Cluster): the normal or colored frame while it is on, else unchanged.
        val base = TurnAlert.decorate(ManeuverMapper.buildManeuver(state, context), context)
        val tests = LaneTestStore.get(context).current
        val lanes =
            when (tests.source) {
                LaneSource.TEST -> LaneGuidance.testPattern(base.type, state.maneuverType * STEP_HASH + (state.roadName?.hashCode() ?: 0))
                LaneSource.IPHONE -> state.lanes
            }
        val step =
            runCatching {
                val builder = Step.Builder().setManeuver(LaneIconComposer.maneuver(base, lanes, tests, context))
                state.stepCue?.let { builder.setCue(it) }
                when {
                    tests.slotCard -> {
                        addLanes(builder, LaneSlotCard.LANES)
                        builder.setLanesImage(LaneSlotCard.image(LaneSlotCard.frameAt(SystemClock.elapsedRealtime())))
                    }
                    tests.strip && lanes.isNotEmpty() -> {
                        addLanes(builder, lanes)
                        builder.setLanesImage(LaneIconComposer.lanesImage(lanes))
                    }
                }
                builder.build()
            }
        step.exceptionOrNull()?.let { logWarn("[LANES] lane step refused (${it.message}); sending the plain step", tag = Logger.Tags.NAVI) }
        return step.getOrNull() ?: plain(base, state)
    }

    private fun addLanes(
        builder: Step.Builder,
        lanes: List<LaneHint>,
    ) {
        lanes.forEach { lane ->
            val directions = Lane.Builder().addDirection(LaneDirection.create(LaneGuidance.shapeFor(lane.angle), lane.recommended))
            lane.others.forEach { directions.addDirection(LaneDirection.create(LaneGuidance.shapeFor(it), false)) }
            builder.addLane(directions.build())
        }
    }

    private fun plain(
        base: Maneuver,
        state: NavigationState,
    ): Step {
        val builder = Step.Builder().setManeuver(base)
        state.stepCue?.let { builder.setCue(it) }
        return builder.build()
    }
}
