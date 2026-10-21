package com.carlink.navigation

import android.content.Context
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Maneuver
import com.carlink.logging.Logger
import com.carlink.logging.logWarn
import com.carlink.ui.settings.AlertTiming
import com.carlink.ui.settings.TurnAlertConfig
import com.carlink.ui.settings.TurnAlertStore

/**
 * Turn alert (Settings › Cluster › Turn alert, v17): as the car nears the next turn, the cluster
 * and HUD turn icon flashes between its normal frame and a colored one, then holds the colored
 * frame for the last [SOLID_SECONDS] if the driver chose that.
 *
 * WHEN. Warning time grows with speed: [MIN_LEAD_S] at [LEAD_LOW_MPH] or slower, [MAX_LEAD_S] at
 * [LEAD_HIGH_MPH] or faster, a straight line between, scaled by Start alerting. Standard gives
 * 0.10 mi at 25 mph and 0.50 mi at 60 mph. Carlink3 cannot read the vehicle's speed, so it is the
 * rate the iPhone's distance-to-turn counts down over the last few updates. Below [MOVING_MPS]
 * (stopped, or the speed is not known yet after a new step) nothing flashes.
 *
 * HOW. [observe] takes each new navigation state; the cluster session's ticker asks [phase] and
 * flips [coloredBeat] at the flash rate, and [decorate] picks the frame for the trip. While the
 * icon is flashing the ticker is the only thing that sends trips, so the cluster sees one change
 * per half cycle and no repeats of the frame already on screen. Both frames come from
 * [TurnAlertFrames]: drawn here, same size and position, reused for the whole step.
 */
object TurnAlert {
    enum class Phase { NORMAL, FLASH, SOLID }

    /** The final approach held on the colored frame when Solid for the last 3 seconds is on. */
    const val SOLID_SECONDS = 3.0

    private const val MIN_LEAD_S = 15.0
    private const val MAX_LEAD_S = 30.0
    private const val LEAD_LOW_MPH = 25.0
    private const val LEAD_HIGH_MPH = 60.0
    const val MPS_PER_MPH = 0.44704
    const val METERS_PER_MILE = 1609.344

    /** About 4.5 mph: slower than this counts as stopped. */
    private const val MOVING_MPS = 2.0

    /** Speed is the countdown over this window... */
    private const val WINDOW_MS = 5_000L

    /** ...once it spans at least this long. */
    private const val MIN_SPAN_MS = 1_500L

    /** The distance growing by more than this means a new step or a re-route: start over. */
    private const val JUMP_M = 30

    /**
     * CPManeuverType values that never flash: noTurn (0), endOfNavigation (10), proceedToRoute (11),
     * arrived (12), arrivedLeft (24), arrivedRight (25), endOfDirections (27).
     */
    private val QUIET_TYPES = setOf(0, 10, 11, 12, 24, 25, 27)

    private class Sample(
        val atMs: Long,
        val meters: Int,
    )

    private val samples = ArrayDeque<Sample>()
    private var stepKey: Pair<Int, String?>? = null

    @Volatile private var active = false

    @Volatile private var maneuverType = 0

    @Volatile private var speedMps = 0.0

    @Volatile private var lastMeters = 0

    @Volatile private var lastAtMs = 0L

    /** The phase the cluster session last acted on. */
    @Volatile var phaseNow = Phase.NORMAL

    /** Which frame a flashing icon shows next. Flipped by the cluster session's ticker. */
    @Volatile var coloredBeat = false

    /** Warning time at [mph], before the Start alerting factor. */
    fun leadSeconds(
        mph: Double,
        timing: AlertTiming,
    ): Double {
        val slope = (MAX_LEAD_S - MIN_LEAD_S) / (LEAD_HIGH_MPH - LEAD_LOW_MPH)
        return (MIN_LEAD_S + (mph - LEAD_LOW_MPH) * slope).coerceIn(MIN_LEAD_S, MAX_LEAD_S) * timing.factor
    }

    /** Distance before the turn where the alert starts, at [mps]. */
    fun startMeters(
        mps: Double,
        timing: AlertTiming,
    ): Double = mps * leadSeconds(mps / MPS_PER_MPH, timing)

    /** Feed each NEW navigation state, never a re-send of the same one. */
    @Synchronized
    fun observe(
        state: NavigationState,
        nowMs: Long,
    ) {
        active = state.isActive
        maneuverType = state.maneuverType
        val key = state.maneuverType to state.roadName
        val jumped = samples.lastOrNull()?.let { state.remainDistance > it.meters + JUMP_M } ?: false
        if (!state.isActive || key != stepKey || jumped) {
            samples.clear()
            stepKey = key
            speedMps = 0.0
        }
        samples.addLast(Sample(nowMs, state.remainDistance))
        while (samples.size > 1 && nowMs - samples.first().atMs > WINDOW_MS) samples.removeFirst()
        val first = samples.first()
        val span = nowMs - first.atMs
        if (span >= MIN_SPAN_MS) {
            speedMps = ((first.meters - state.remainDistance) * 1000.0 / span).coerceAtLeast(0.0)
        }
        lastMeters = state.remainDistance
        lastAtMs = nowMs
    }

    /** What the icon should be doing at [nowMs]. The distance is carried forward between updates. */
    fun phase(
        cfg: TurnAlertConfig,
        nowMs: Long,
    ): Phase {
        val v = speedMps
        return when {
            !cfg.enabled || !active || maneuverType in QUIET_TYPES || v < MOVING_MPS -> Phase.NORMAL
            else -> {
                val remain = (lastMeters - v * (nowMs - lastAtMs) / 1000.0).coerceAtLeast(0.0)
                when {
                    remain > startMeters(v, cfg.timing) -> Phase.NORMAL
                    cfg.solidLast && remain / v <= SOLID_SECONDS -> Phase.SOLID
                    else -> Phase.FLASH
                }
            }
        }
    }

    /** Speed and carried-forward distance, for the phase-change log line. */
    fun describe(nowMs: Long): String {
        val v = speedMps
        val remain = (lastMeters - v * (nowMs - lastAtMs) / 1000.0).coerceAtLeast(0.0)
        return "%.0f mph, %.0f m to the turn".format(v / MPS_PER_MPH, remain)
    }

    /** [base] with the frame for [phaseNow]; [base] itself outside an alert. */
    fun decorate(
        base: Maneuver,
        context: Context,
    ): Maneuver {
        val p = phaseNow
        if (p == Phase.NORMAL) return base
        val cfg = TurnAlertStore.get(context).current
        val icon = base.icon ?: return base
        val frames = TurnAlertFrames.frames(icon, cfg.color.argb, cfg.style, context) ?: return base
        val pick: CarIcon = if (p == Phase.SOLID || coloredBeat) frames.colored else frames.normal
        return runCatching {
            val b = Maneuver.Builder(base.type)
            if (base.roundaboutExitNumber > 0) b.setRoundaboutExitNumber(base.roundaboutExitNumber)
            if (base.roundaboutExitAngle > 0) b.setRoundaboutExitAngle(base.roundaboutExitAngle)
            b.setIcon(pick).build()
        }.getOrElse {
            logWarn("[TURN_ALERT] could not rebuild the maneuver (${it.message}); sending the plain one", tag = Logger.Tags.NAVI)
            base
        }
    }

    /** Forget the step being tracked (tests). */
    @Synchronized
    internal fun reset() {
        samples.clear()
        stepKey = null
        active = false
        maneuverType = 0
        speedMps = 0.0
        lastMeters = 0
        lastAtMs = 0L
        phaseNow = Phase.NORMAL
        coloredBeat = false
    }
}
