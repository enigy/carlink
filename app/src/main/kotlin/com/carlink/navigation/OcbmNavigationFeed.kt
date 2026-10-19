package com.carlink.navigation

import android.os.Handler
import android.os.Looper
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logNavi
import com.carlink.navigation.compose.ComposedIconStore
import org.json.JSONObject

/**
 * Carlink3's route-guidance source: the OCBM box decodes the iPhone's iAP2 route guidance and
 * forwards it as JSON on `CH_METADATA` (`iap2-core/src/metadata.rs`):
 *
 * - `kind=routeGuidance` (0x5201, ~1/s): routeGuidanceState, distanceToManeuverM,
 *   distanceRemainingM, timeRemainingSec, destinationName, **currentManeuverIndex**, …
 * - `kind=maneuver` (0x5202, one per maneuver of the route, keyed by `index`): maneuverType,
 *   description, afterManeuverRoadName, distanceM, drivingSide, junctionType, **exitInfo**.
 *
 * This object turns those into the [NavigationState] Carlink2's cluster/HUD pipeline already
 * renders ([TripBuilder], [ManeuverMapper], the compose icon store), replacing Carlink2's
 * NaviJSON path (`NavigationStateManager.onNaviJson`), which read the stock box's pre-digested
 * fields. Maneuvers are held by index; the live one is whatever the last 0x5201 named.
 *
 * Junction spoke angles (0x5202 params 10/11) arrive as `junctionAngles`/`junctionExitAngle` from
 * a box built from the OCBM branch `route-guidance-exitinfo-angles`; without them the composer
 * falls back to the static glyph per maneuverType. Roundabout exit counts come from the type
 * (ManeuverMapper). `drivingSide` and
 * `junctionType` are Apple enums whose values are not yet confirmed against the Kit, so they are
 * carried on the maneuver record but not mapped onto [NavigationState.turnSide]/[NavigationState.junctionType]
 * until a capture pins them.
 *
 * Thread-safety: called from the OCBM read-side dispatch; a single lock serializes updates.
 */
object OcbmNavigationFeed {
    private val lock = Any()

    /**
     * End-of-guidance grace. The 2026-10-19 wired drive showed `routeGuidanceState=0` records
     * arriving every few seconds in the middle of a live route, each followed within a second by a
     * fresh full maneuver list. Clearing on every 0 made the cluster trip flap (clear, refocus,
     * recompose 24 maneuvers, repeat) and the HUD never settled. Now a 0 only arms a timer; the
     * trip is cleared if nothing positive follows within [END_GRACE_MS]. A real end still clears.
     */
    private const val END_GRACE_MS = 6_000L
    private val main = Handler(Looper.getMainLooper())
    private var endPending = false
    private var zeroRecords = 0L
    private val endRunnable =
        Runnable {
            synchronized(lock) {
                if (!endPending) return@Runnable
                endPending = false
                logInfo("[NAVI] OCBM route guidance ended — clearing (no update for ${END_GRACE_MS / 1000}s)", tag = Logger.Tags.NAVI)
                clearLocked()
            }
        }
    private val maneuvers = sortedMapOf<Int, Iap2ManeuverData>()
    private var currentIndex: Int? = null
    private var guidance = NavigationState()
    private var routeDirty = false
    private var published = 0L

    /** `kind=routeGuidance` — trip summary and the index of the maneuver being approached. */
    fun onRouteGuidance(obj: JSONObject) {
        synchronized(lock) {
            val active = obj.optInt("routeGuidanceState", -1)
            if (active == 0) {
                // Logged once per run of zeros, not per record: the drive showed them every ~4 s.
                if (!endPending) logInfo("[NAVI] OCBM route guidance state 0 — clearing in ${END_GRACE_MS / 1000}s unless guidance resumes", tag = Logger.Tags.NAVI)
                zeroRecords++
                endPending = true
                main.removeCallbacks(endRunnable)
                main.postDelayed(endRunnable, END_GRACE_MS)
                return
            }
            if (endPending) {
                logInfo("[NAVI] OCBM route guidance resumed (state=$active) after $zeroRecords zero record(s) — keeping the trip", tag = Logger.Tags.NAVI)
                endPending = false
                zeroRecords = 0
                main.removeCallbacks(endRunnable)
            }
            if (obj.has("currentManeuverIndex")) currentIndex = obj.optInt("currentManeuverIndex")
            guidance =
                guidance.copy(
                    status = if (active > 0) 1 else guidance.status,
                    remainDistance = obj.optIntOr("distanceToManeuverM", guidance.remainDistance),
                    distanceToDestination = obj.optIntOr("distanceRemainingM", guidance.distanceToDestination),
                    timeToDestination = obj.optIntOr("timeRemainingSec", guidance.timeToDestination),
                    destinationName = obj.optString("destinationName").takeIf { it.isNotEmpty() } ?: guidance.destinationName,
                )
            if (routeDirty) {
                ComposedIconStore.populateFromRoute(Iap2RouteData(maneuvers.values.toList()))
                routeDirty = false
            }
            publishLocked()
        }
    }

    /** `kind=maneuver` — one maneuver of the route; the box sends the whole list, one message each. */
    fun onManeuver(obj: JSONObject) {
        if (!obj.has("index")) return
        synchronized(lock) {
            val index = obj.optInt("index")
            val m =
                Iap2ManeuverData(
                    index = index,
                    cpManeuverType = obj.optInt("maneuverType", 0),
                    instructionText = obj.optString("description"),
                    postManeuverRoadName = obj.optString("afterManeuverRoadName"),
                    distanceMeters = obj.optInt("distanceM", 0),
                    distanceDisplayText = obj.optString("distanceText"),
                    distanceUnitFlag = obj.optInt("distanceUnits", 0),
                    flag8 = obj.optInt("drivingSide", 0),
                    flag9 = obj.optInt("junctionType", 0),
                    // Present once the box emits 0x5202 params 10/11 (OCBM branch
                    // route-guidance-exitinfo-angles); absent on older box builds, which leaves the
                    // composer on the static glyph per maneuver type.
                    entryAngles = obj.optJSONArray("junctionAngles")?.let { arr -> List(arr.length()) { arr.optInt(it) } } ?: emptyList(),
                    exitAngle = if (obj.has("junctionExitAngle")) obj.optInt("junctionExitAngle") else null,
                    exitInfo = obj.optString("exitInfo").takeIf { it.isNotEmpty() },
                )
            val changed = maneuvers[index] != m
            maneuvers[index] = m
            if (changed) routeDirty = true
            if (m.exitInfo != null) {
                logInfo(
                    "[EXIT_INFO] maneuver idx=$index cpType=${m.cpManeuverType} exit='${m.exitInfo}' " +
                        "instruction='${m.instructionText}'",
                    tag = Logger.Tags.NAVI,
                )
            }
            // Republish only if this maneuver is the live one or its successor — otherwise wait for 0x5201.
            val cur = currentIndex
            val isLiveOrNext = cur != null && (index == cur || index == nextIndexAfter(cur))
            if (changed && isLiveOrNext) publishLocked()
        }
    }

    fun clear() {
        synchronized(lock) { clearLocked() }
    }

    /** Snapshot for diagnostics: maneuvers held and the live index. */
    fun describe(): String = synchronized(lock) { "maneuvers=${maneuvers.size} current=$currentIndex published=$published" }

    private fun clearLocked() {
        endPending = false
        zeroRecords = 0
        main.removeCallbacks(endRunnable)
        maneuvers.clear()
        currentIndex = null
        guidance = NavigationState()
        routeDirty = false
        ComposedIconStore.clear()
        NavigationStateManager.clear()
    }

    private fun nextIndexAfter(index: Int): Int? = maneuvers.keys.firstOrNull { it > index }

    private fun publishLocked() {
        val cur = currentIndex
        val step = cur?.let { maneuvers[it] }
        val next = cur?.let { nextIndexAfter(it) }?.let { maneuvers[it] }
        val state =
            if (step == null) {
                guidance
            } else {
                guidance.copy(
                    maneuverType = step.cpManeuverType,
                    roadName = step.instructionText.takeIf { it.isNotEmpty() } ?: step.postManeuverRoadName.takeIf { it.isNotEmpty() } ?: guidance.roadName,
                    exitAngle = step.exitAngle,
                    exitInfo = step.exitInfo,
                    nextManeuverType = next?.cpManeuverType,
                    nextRoadName = next?.instructionText?.takeIf { it.isNotEmpty() } ?: next?.postManeuverRoadName,
                    nextExitAngle = next?.exitAngle,
                    nextExitInfo = next?.exitInfo,
                )
            }
        published++
        logNavi {
            "[NAVI] OCBM publish #$published cur=$cur type=${state.maneuverType} road=${state.roadName} " +
                "exit=${state.exitInfo} remain=${state.remainDistance}m next=${state.nextManeuverType}@${state.nextRoadName}"
        }
        NavigationStateManager.publish(state)
    }

    private fun JSONObject.optIntOr(
        key: String,
        fallback: Int,
    ): Int = if (has(key)) optInt(key, fallback) else fallback
}
