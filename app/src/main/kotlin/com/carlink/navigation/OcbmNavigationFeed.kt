package com.carlink.navigation

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
 * Not available from OCBM today, so left at Carlink2's defaults: junction spoke angles
 * (0x5202 params 10/11 — the composer falls back to the static glyph per maneuverType) and
 * roundabout exit counts (ManeuverMapper derives those from the type). `drivingSide` and
 * `junctionType` are Apple enums whose values are not yet confirmed against the Kit, so they are
 * carried on the maneuver record but not mapped onto [NavigationState.turnSide]/[NavigationState.junctionType]
 * until a capture pins them.
 *
 * Thread-safety: called from the OCBM read-side dispatch; a single lock serializes updates.
 */
object OcbmNavigationFeed {
    private val lock = Any()
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
                logInfo("[NAVI] OCBM route guidance ended — clearing", tag = Logger.Tags.NAVI)
                clearLocked()
                return
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
                    entryAngles = emptyList(),
                    exitAngle = null,
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
