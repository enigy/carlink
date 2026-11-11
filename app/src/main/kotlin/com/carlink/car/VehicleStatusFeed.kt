package com.carlink.car

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The car's status for the phone (Settings › Vehicle). The adapter declares what the driver shares; when the phone
 * asks (StartVehicleStatusUpdates, which the box passes on as a `{"kind":"vehicleStatus"}` record), this sends the
 * readings it wants ([VehicleStatusValues.encode]) at once and again each time one changes, until the box says stop
 * or the session ends. Range goes in whole km and the temperature in whole °C, so only a real change is sent.
 */
class VehicleStatusFeed(
    private val scope: CoroutineScope,
    /** What the driver shares now: a share turned off mid-session stops at once. */
    private val shares: StateFlow<VehicleStatusValues.Shares>,
    /** Sends one `INPUT_VEHICLE_STATUS` body; false when it was dropped. Any thread. */
    private val send: (ByteArray) -> Boolean,
) {
    private var job: Job? = null

    /** What the phone asked for, of what was declared; empty when it isn't asking. */
    @Volatile
    var wanted: Set<Int> = emptySet()
        private set

    /** One `{"kind":"vehicleStatus"}` record from the box. */
    @Synchronized
    fun onRecord(obj: JSONObject) {
        val asked = obj.optJSONArray("want")
        val want = if (obj.optString("state") == "start" && asked != null) (0 until asked.length()).map { asked.optInt(it) }.toSet() else emptySet()
        stop()
        wanted = want
        if (want.isEmpty()) return
        job =
            scope.launch {
                VehicleReadings.state
                    .combine(shares) { state, s -> VehicleStatusValues.encode(state, want intersect s.ids) }
                    .map { it.toList() }
                    .distinctUntilChanged()
                    .filter { it.isNotEmpty() }
                    .collect { send(it.toByteArray()) }
            }
    }

    /** The phone stopped asking, or the session ended. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        wanted = emptySet()
    }
}
