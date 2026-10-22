package com.carlink.cluster

import android.content.Intent
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.Trip
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.carlink.logging.Logger
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.logging.logNavi
import com.carlink.logging.logWarn
import com.carlink.navigation.NavigationState
import com.carlink.navigation.NavigationStateManager
import com.carlink.navigation.TripBuilder
import com.carlink.navigation.TurnAlert
import com.carlink.ui.settings.TurnAlertStore
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * GM AAOS cluster session — relays Trip data via NavigationManager.updateTrip().
 *
 * This is the ACTIVE session returned by [CarlinkClusterService]. It works on GM AAOS
 * because GM has an internal cluster manager (OnStarTurnByTurnManager) that consumes
 * NavigationManager data and renders turn-by-turn on the instrument cluster. The
 * [RelayScreen] is never visible — GM's system ignores it.
 *
 * **GM-specific**: On non-GM AAOS platforms that render Screen.onGetTemplate() directly,
 * this session would show static text instead of navigation data. See [CarlinkClusterSession]
 * for the standard Car App Library approach needed on those platforms.
 *
 * Primary/secondary multiplexing is defensive against dual-session creation observed
 * in the AAOS emulator (DISPLAY_TYPE_MAIN + DISPLAY_TYPE_CLUSTER; not documented by
 * androidx). GM AAOS behavior is STILL UNVERIFIED at runtime — 2026-04-20 POTATO
 * captures show this session never instantiated on gminfo37 (cluster_navigation_enabled
 * preference off by default). GM's cluster is actually driven by VMSClusterService +
 * NavigationClusterService + OnStarTurnByTurnManager (logcat 063729_cluster.txt:659,
 * 703-705) — not the androidx CarAppService binding chain. Primary-claim logic: first
 * session obtains NavigationManager and drives the relay; subsequent sessions return
 * inert RelayScreen.
 */
class ClusterMainSession : Session() {
    private var navigationManager: NavigationManager? = null
    private var scope: CoroutineScope? = null
    private var isNavigating = false

    // Set once in onCreateScreen; never mutated thereafter. Effectively a val for the
    // session's lifetime — the onDestroy observer reads it to decide cleanup scope.
    private var isPrimary = false

    /** Only call navigationEnded() after we've seen at least one active state transition to idle.
     *  Without this, the initial idle state from NavigationStateManager kills the binding chain
     *  before Templates Host can create the cluster session (displayType=1).
     *  Never reset to false — acts as a one-way latch for the session's lifetime. If the session
     *  is ever reused across multiple trips, this flag will need a reset path. */
    private var hasSeenActiveNav = false

    /** Pending arrival timeout — fires navigationEnded() if adapter doesn't send NaviStatus=0
     *  after arrival (a terminal maneuver within ARRIVAL_ZONE_M). */
    private var arrivalTimeoutJob: Job? = null

    /**
     * Set true once we've relayed arrival (a terminal maneuver within ARRIVAL_ZONE_M); cleared
     * by any update short of arrival (a fresh trip, or the final stretch). While latched we suppress the
     * navigationEnded()→navigationStarted() re-start churn caused by the adapter oscillating
     * NaviStatus and re-sending the "arrived" frame at trip end — that churn made the cluster nav
     * card flicker (disappear/reappear) repeatedly during the final approach. The arrival is shown
     * once, navigation ends once, and it stays ended until a new trip begins.
     */
    private var arrivalLatched = false

    /**
     * The resend burst after navigation (re)starts ([startResendBurst]). GM drops trips that arrive
     * while it is still putting up its cluster card, and a parked car at the start of a route sends
     * no more: v17 showed a blank HUD at "Start on …" (2026-10-21) where the flash test, which
     * re-sent every 0.5 s, showed the card.
     */
    private var resendJob: Job? = null

    /** Whether the last state seen was an active route, so a route starting can start a burst. */
    private var lastActive = false

    /** Step type of the last logged trip, so [logTrip] speaks on a new step and not on every tick. */
    private var lastLoggedType: Int? = null

    companion object {
        /** Terminal CPManeuverType values that indicate navigation is complete. */
        private val TERMINAL_MANEUVER_TYPES = intArrayOf(
            10, // endOfNavigation
            12, // arrived
            24, // arrivedLeft
            25, // arrivedRight
            27, // endOfDirections
        )

        /** Grace period for adapter to send NaviStatus=0 after arrival. */
        private const val ARRIVAL_TIMEOUT_MS = 10_000L

        /**
         * [207] A terminal type counts as "arrived" only this close to the destination. The
         * arrive-left/right step is current for the whole last stretch of road, counting down
         * (truck log 2026-10-09: became current at 297 m). Timing out from the moment it became
         * current ended HUD navigation ~200 m (650–700 ft) short of the destination. The last
         * distances Apple reports before arriving were 17–22 m.
         */
        private const val ARRIVAL_ZONE_M = 50

        /** How often the turn alert checks its phase while the icon is not flashing. */
        private const val ALERT_POLL_MS = 250L

        /** The resend burst: one trip per [RESEND_INTERVAL_MS], [RESEND_COUNT] times. */
        private const val RESEND_INTERVAL_MS = 1_000L
        private const val RESEND_COUNT = 10

        /** Outside a burst or a new step, one trip line per this many trips. */
        private const val TRIP_LOG_EVERY = 30L

        /** First live session wins; cleared on destroy so a fresh binding chain can take over. */
        private val primarySession = AtomicReference<ClusterMainSession?>(null)
    }

    override fun onCreateScreen(intent: Intent): Screen {
        ClusterBindingState.sessionAlive = true

        // Claim primary role if no other session holds it (atomic CAS — no TOCTOU).
        isPrimary = primarySession.compareAndSet(null, this)
        if (isPrimary) {
            logInfo("[CLUSTER_MAIN] Primary session created — owns NavigationManager", tag = Logger.Tags.CLUSTER)
        } else {
            logInfo("[CLUSTER_MAIN] Secondary session created — passive (no NavigationManager calls)", tag = Logger.Tags.CLUSTER)
            return RelayScreen(carContext)
        }

        // --- Everything below runs only for the primary session ---

        // Get NavigationManager — needed for navigationStarted() which triggers cluster creation
        try {
            navigationManager = carContext.getCarService(NavigationManager::class.java)
            logInfo("[CLUSTER_MAIN] NavigationManager obtained", tag = Logger.Tags.CLUSTER)
        } catch (e: Exception) {
            logError(
                "[CLUSTER_MAIN] Failed to get NavigationManager: ${e.message}",
                tag = Logger.Tags.CLUSTER,
                throwable = e,
            )
        }

        // Set NavigationManagerCallback BEFORE calling navigationStarted() — Templates Host
        // requires the callback to be set first, otherwise navigationStarted() throws.
        navigationManager?.setNavigationManagerCallback(
            object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    logInfo("[CLUSTER_MAIN] onStopNavigation callback", tag = Logger.Tags.CLUSTER)
                    isNavigating = false
                }

                override fun onAutoDriveEnabled() {
                    logNavi { "[CLUSTER_MAIN] Auto drive enabled" }
                }
            },
        )

        // Call navigationStarted() IMMEDIATELY — this is the critical trigger that causes
        // Templates Host to create ClusterTurnCardActivity on the cluster display.
        // Without this, Templates Host never creates the cluster display.
        try {
            navigationManager?.navigationStarted()
            isNavigating = true
            logInfo("[CLUSTER_MAIN] navigationStarted() called", tag = Logger.Tags.CLUSTER)
        } catch (e: Exception) {
            logWarn("[CLUSTER_MAIN] navigationStarted() failed: ${e.message}", tag = Logger.Tags.CLUSTER)
        }

        // Observe NavigationStateManager to relay Trip updates
        val sessionScope = CoroutineScope(Dispatchers.Main)
        scope = sessionScope

        sessionScope.launch {
            collectNavigationState()
        }
        sessionScope.launch {
            turnAlertTicker()
        }
        // A cluster (re)connect mid-route: GM may not take the first trip, and a parked car sends
        // no more, so resend the current one for a few seconds.
        startResendBurst("cluster session created")

        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    if (isPrimary) {
                        // Only the CURRENT primary clears the shared flag. The CAS fails when a
                        // newer session already claimed primary (e.g. this onDestroy is a late
                        // teardown of the previous session, dispatched after restartClusterBinding
                        // already stood up a replacement) — in that case we must NOT flip
                        // sessionAlive to false, or we'd blank a live session.
                        val wasCurrentPrimary = primarySession.compareAndSet(this@ClusterMainSession, null)
                        logInfo(
                            "[CLUSTER_MAIN] Primary session destroyed — releasing NavigationManager ownership" +
                                if (!wasCurrentPrimary) " (superseded — leaving sessionAlive untouched)" else "",
                            tag = Logger.Tags.CLUSTER,
                        )
                        arrivalTimeoutJob?.cancel()
                        arrivalTimeoutJob = null
                        if (isNavigating) {
                            try {
                                navigationManager?.navigationEnded()
                                logNavi { "[CLUSTER_MAIN] navigationEnded() called on destroy" }
                            } catch (e: Exception) {
                                logError(
                                    "[CLUSTER_MAIN] navigationEnded() failed on destroy: ${e.message}",
                                    tag = Logger.Tags.CLUSTER,
                                    throwable = e,
                                )
                            }
                            isNavigating = false
                        }
                        scope?.cancel()
                        scope = null
                        navigationManager = null
                        if (wasCurrentPrimary) {
                            ClusterBindingState.sessionAlive = false
                        }
                    } else {
                        logInfo("[CLUSTER_MAIN] Secondary session destroyed", tag = Logger.Tags.CLUSTER)
                    }
                }
            },
        )

        return RelayScreen(carContext)
    }

    /**
     * Collect navigation state with 200ms debounce.
     *
     * collectLatest already cancels the previous suspended block on new emissions, so the
     * explicit debounceJob?.cancel() is belt-and-suspenders — kept to make the debounce
     * intent obvious at the call site.
     */
    private suspend fun collectNavigationState() {
        var debounceJob: Job? = null

        NavigationStateManager.state.collectLatest { state ->
            debounceJob?.cancel()

            debounceJob =
                scope?.launch {
                    delay(200)
                    onNewState(state)
                }
        }
    }

    /**
     * A new navigation state: the turn alert learns the distance from it, then it goes to the
     * cluster, unless the icon is flashing. Then the ticker sends the latest state on its next beat,
     * so the cluster never gets a second trip carrying the frame it already shows.
     */
    private fun onNewState(state: NavigationState) {
        val now = SystemClock.elapsedRealtime()
        val routeStarting = state.isActive && !lastActive
        lastActive = state.isActive
        TurnAlert.observe(state, now)
        val phase = TurnAlert.phase(TurnAlertStore.get(carContext).current, now)
        if (phase != TurnAlert.Phase.FLASH) setAlertPhase(phase, now)
        if (TurnAlert.phaseNow == TurnAlert.Phase.FLASH && state.isActive && isNavigating) return
        processStateUpdate(state)
        if (routeStarting) startResendBurst("route started")
    }

    /**
     * Resend the current trip once a second for [RESEND_COUNT] seconds. GM drops trips that land
     * while it is still creating its cluster turn card, and when the car is parked the state does
     * not change, so v17 sent nothing more and the HUD stayed blank (the flash test build, which
     * re-sent twice a second, showed the card). A short burst rather than a steady repeat: identical
     * trips sent continuously made the HUD redraw the icon larger every few flashes. Skipped while
     * the turn alert flashes, when its ticker is the only sender.
     */
    private fun startResendBurst(why: String) {
        resendJob?.cancel()
        resendJob =
            scope?.launch {
                logInfo("[CLUSTER_MAIN] resending the trip for ${RESEND_COUNT}s ($why)", tag = Logger.Tags.CLUSTER)
                repeat(RESEND_COUNT) { i ->
                    delay(RESEND_INTERVAL_MS)
                    val state = NavigationStateManager.state.value
                    if (state.isActive && isNavigating && TurnAlert.phaseNow != TurnAlert.Phase.FLASH) {
                        processStateUpdate(state, "resend ${i + 1}/$RESEND_COUNT")
                    }
                }
            }
    }

    /**
     * One line per trip on a new step and during a resend burst, else every [TRIP_LOG_EVERY]th:
     * release builds log nothing else about trips, so a blank HUD was invisible in the drive log.
     * Step type, distance and the icon's kind only (no road names).
     */
    private fun logTrip(
        trip: Trip,
        state: NavigationState,
        sent: Long,
        reason: String,
    ) {
        val newStep = lastLoggedType != state.maneuverType
        if (!newStep && !reason.startsWith("resend") && sent % TRIP_LOG_EVERY != 0L) return
        lastLoggedType = state.maneuverType
        val icon =
            when (trip.steps.firstOrNull()?.maneuver?.icon?.icon?.type) {
                IconCompat.TYPE_BITMAP -> "bitmap"
                IconCompat.TYPE_RESOURCE -> "resource"
                null -> "none"
                else -> "other"
            }
        logInfo(
            "[CLUSTER_MAIN] trip #$sent sent ($reason): cpType=${state.maneuverType} dist=${state.remainDistance}m icon=$icon " +
                "lanes=${trip.steps.firstOrNull()?.lanes?.size ?: 0}",
            tag = Logger.Tags.CLUSTER,
        )
    }

    /**
     * Turn alert (Settings › Cluster): flips the flash beat at the chosen speed and is the only
     * sender of trips while the icon flashes; outside that it checks the phase every
     * [ALERT_POLL_MS] and sends one trip when it changes (start of the solid frame, end of the alert).
     */
    private suspend fun turnAlertTicker() {
        while (true) {
            val cfg = TurnAlertStore.get(carContext).current
            val now = SystemClock.elapsedRealtime()
            val phase = TurnAlert.phase(cfg, now)
            val changed = setAlertPhase(phase, now)
            val state = NavigationStateManager.state.value
            val canSend = state.isActive && isNavigating
            if (phase == TurnAlert.Phase.FLASH) {
                TurnAlert.coloredBeat = !TurnAlert.coloredBeat
                if (canSend) processStateUpdate(state)
                delay(cfg.speed.cycleMs / 2)
            } else {
                if (changed && canSend) processStateUpdate(state)
                delay(ALERT_POLL_MS)
            }
        }
    }

    /** Record [phase]; true when it changed. Logged: the drive log is how an alert is checked after a drive. */
    private fun setAlertPhase(
        phase: TurnAlert.Phase,
        now: Long,
    ): Boolean {
        val was = TurnAlert.phaseNow
        if (phase == was) return false
        TurnAlert.phaseNow = phase
        TurnAlert.coloredBeat = false
        logInfo("[TURN_ALERT] $was -> $phase (${TurnAlert.describe(now)})", tag = Logger.Tags.CLUSTER)
        return true
    }

    // Translates navigation state into NavigationManager Trip updates; branch count = state cases.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun processStateUpdate(
        state: NavigationState,
        reason: String = "state",
    ) {
        val navManager = navigationManager
        if (navManager == null) {
            logWarn("[CLUSTER_MAIN] NavigationManager is null — cannot relay", tag = Logger.Tags.CLUSTER)
            return
        }

        if (state.isActive) {
            hasSeenActiveNav = true
            // Terminal types are approached like any other step; only within ARRIVAL_ZONE_M is
            // the trip actually over ([207]).
            val arrived = state.maneuverType in TERMINAL_MANEUVER_TYPES && state.remainDistance <= ARRIVAL_ZONE_M

            // Anything short of arrival — a new trip, or still driving the final stretch — drops
            // the arrival latch so navigation can re-start normally (e.g. after a mid-drive flush).
            if (!arrived) arrivalLatched = false

            // Re-enter navigation if a prior path cleared isNavigating: adapter flush
            // (isIdle branch below), onStopNavigation callback from the Host, or the
            // arrival-timeout auto-end. New active data means a fresh trip is starting.
            if (!isNavigating) {
                // Anti-flicker: once we've arrived and ended, do NOT re-start navigation for the
                // repeated "arrived" frames the adapter keeps sending (it oscillates NaviStatus at
                // trip end). Re-starting here just to immediately re-show "arrived" and re-arm the
                // timeout is what made the cluster card flicker. Only a step short of arrival (new
                // trip, or the final stretch — latch cleared above) gets past this guard.
                if (arrived && arrivalLatched) {
                    return
                }
                logInfo("[CLUSTER_MAIN] navigationStarted() (re-start)", tag = Logger.Tags.CLUSTER)
                try {
                    navManager.navigationStarted()
                    isNavigating = true
                    startResendBurst("navigation re-started")
                } catch (e: Exception) {
                    logError(
                        "[CLUSTER_MAIN] navigationStarted() failed: ${e.message}",
                        tag = Logger.Tags.CLUSTER,
                        throwable = e,
                    )
                    return
                }
            }

            try {
                val trip = TripBuilder.buildTrip(state, carContext)
                navManager.updateTrip(trip)
                // Nav-output heartbeat for the [NAV_HEALTH] diagnostic. See
                // [ClusterBindingState.lastRelayElapsedMs].
                ClusterBindingState.lastRelayElapsedMs = SystemClock.elapsedRealtime()
                val sent = ClusterBindingState.relayCount.incrementAndGet()
                logTrip(trip, state, sent, reason)
                logNavi {
                    "[CLUSTER_MAIN] Trip relayed: maneuver=${state.maneuverType}, " +
                        "dist=${state.remainDistance}m, road=${state.roadName}" +
                        if (state.hasNextStep) ", nextManeuver=${state.nextManeuverType}, nextRoad=${state.nextRoadName}" else ""
                }
            } catch (e: Exception) {
                logError(
                    "[CLUSTER_MAIN] updateTrip() failed: ${e.message}",
                    tag = Logger.Tags.CLUSTER,
                    throwable = e,
                )
            }

            // Arrival timeout: once arrived (terminal type within ARRIVAL_ZONE_M) start a grace
            // period. If the adapter doesn't send NaviStatus=0 within the window, end navigation
            // ourselves. Catches firmware gap where arrival is sent without flush.
            if (arrived) {
                // Latch arrival: we've now shown the arrival card, so subsequent terminal frames
                // (after navigation ends) are suppressed by the re-start guard above.
                arrivalLatched = true
                // Only start a timeout if none is pending — the window is NOT reset by
                // subsequent terminal-maneuver updates within the same arrival burst.
                if (arrivalTimeoutJob?.isActive != true) {
                    logInfo(
                        "[CLUSTER_MAIN] Arrived (cpType=${state.maneuverType}, ${state.remainDistance}m left) — " +
                            "starting ${ARRIVAL_TIMEOUT_MS / 1000}s arrival timeout",
                        tag = Logger.Tags.CLUSTER,
                    )
                    arrivalTimeoutJob = scope?.launch {
                        delay(ARRIVAL_TIMEOUT_MS)
                        if (isNavigating) {
                            logInfo(
                                "[CLUSTER_MAIN] Arrival timeout — adapter did not send flush, ending navigation",
                                tag = Logger.Tags.CLUSTER,
                            )
                            try {
                                navManager.navigationEnded()
                            } catch (e: Exception) {
                                logError(
                                    "[CLUSTER_MAIN] navigationEnded() failed (arrival timeout): ${e.message}",
                                    tag = Logger.Tags.CLUSTER,
                                    throwable = e,
                                )
                            }
                            isNavigating = false
                        }
                    }
                }
            } else {
                // Not arrived (any other step, or the final stretch) — cancel any pending arrival
                // timeout, e.g. one armed by a stale 0 m reading before the real distance came in.
                arrivalTimeoutJob?.cancel()
                arrivalTimeoutJob = null
            }
        } else if (state.isIdle && isNavigating && hasSeenActiveNav) {
            // Only end navigation if we previously saw active nav data.
            // The initial idle state must NOT kill the binding chain.
            arrivalTimeoutJob?.cancel()
            arrivalTimeoutJob = null
            logInfo("[CLUSTER_MAIN] navigationEnded() (flush signal)", tag = Logger.Tags.CLUSTER)
            try {
                navManager.navigationEnded()
            } catch (e: Exception) {
                logError(
                    "[CLUSTER_MAIN] navigationEnded() failed: ${e.message}",
                    tag = Logger.Tags.CLUSTER,
                    throwable = e,
                )
            }
            isNavigating = false
        }
    }

    /**
     * Relay screen — shows a brief identifying message while Templates Host binds
     * the cluster session. Visible for ~1s before MainActivity returns to front
     * (pinned to the 1000ms postDelayed in MainActivity.launchCarAppActivity).
     *
     * On GM AAOS this screen is never rendered — GM's OnStarTurnByTurnManager owns
     * the cluster display and ignores the Car App Library Screen. The message text
     * is defensive: it only surfaces on non-GM platforms if this session is ever
     * returned there (it shouldn't be; CarlinkClusterSession is the correct choice).
     */
    private class RelayScreen(
        carContext: CarContext,
    ) : Screen(carContext) {
        override fun onGetTemplate(): Template =
            NavigationTemplate
                .Builder()
                .setNavigationInfo(
                    MessageInfo
                        .Builder("Carlink — Cluster Navigation Service")
                        .setText(
                            "Main app should appear momentarily. If this screen persists, return to the app launcher and reopen Carlink.",
                        ).build(),
                ).setActionStrip(
                    ActionStrip
                        .Builder()
                        .addAction(Action.APP_ICON)
                        .build(),
                ).build()
    }
}
