package com.carlink.car

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.navigation.TurnAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The truck's own speed and outside temperature, from the car's property service: the pane's Vehicle
 * card shows them, and the cluster's turn alert times its flashing on the speed ([TurnAlert.onCarSpeed]).
 *
 * Permissions (AOSP car service manifest, android12L-release): `CAR_SPEED` is dangerous, in the
 * Location group, asked for at start-up by MainActivity; `CAR_EXTERIOR_ENVIRONMENT` and
 * `READ_CAR_DISPLAY_UNITS` are normal. Whether a car serves them to a third-party app is the maker's
 * call and published nowhere; the truck does (1.06 probe, owner's drive 10-26: speed live 0-22 mph,
 * outside 16.5 °C, mph and miles). The properties this app can see are still logged at each connect,
 * in case another car differs. A reading is null until it comes, and stays null where the car says no.
 */
object VehicleData {
    /** The one runtime permission: `CAR_SPEED` is dangerous. */
    const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"

    private val stateFlow = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = stateFlow.asStateFlow()

    /** Whether any reading has come; changes far less often than [state], so the pane's deck follows this. */
    private val availableFlow = MutableStateFlow(false)
    val available: StateFlow<Boolean> = availableFlow.asStateFlow()

    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private val subscribed = mutableSetOf<Int>()

    private val callback =
        object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) = take(value)

            override fun onErrorEvent(
                propId: Int,
                areaId: Int,
            ) {
                logWarn("[VEHICLE] ${VehiclePropertyIds.toString(propId)} error event (area $areaId)", tag = Logger.Tags.ADAPTR)
            }
        }

    /**
     * Idempotent; a no-op off automotive. Call again once the speed prompt is answered: a property that
     * became readable is then read and watched. While the car service is still connecting, its ready
     * callback does the reading.
     */
    fun start(context: Context) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        val existing = car
        if (existing != null) {
            if (existing.isConnected) connect(existing, context)
            return
        }
        runCatching {
            Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (ready) connect(c, context) else logWarn("[VEHICLE] car service disconnected", tag = Logger.Tags.ADAPTR)
            }
        }.onSuccess { car = it }
            .onFailure { logWarn("[VEHICLE] Car.createCar failed: ${it.message}", tag = Logger.Tags.ADAPTR) }
    }

    fun stop() {
        runCatching { props?.unregisterCallback(callback) }
        props = null
        subscribed.clear()
        runCatching { car?.disconnect() }
        car = null
        stateFlow.value = VehicleState()
        availableFlow.value = false
    }

    private fun connect(
        c: Car,
        context: Context,
    ) {
        val mgr = runCatching { c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }.getOrNull()
        if (mgr == null) {
            logWarn("[VEHICLE] CarPropertyManager unavailable", tag = Logger.Tags.ADAPTR)
            return
        }
        props = mgr
        val speedAllowed = context.checkSelfPermission(SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED
        val visible =
            runCatching { mgr.propertyList.map { it.propertyId } }
                .onFailure { logWarn("[VEHICLE] property list failed: ${it.javaClass.simpleName}: ${it.message}", tag = Logger.Tags.ADAPTR) }
                .getOrDefault(emptyList())
        val names = visible.map(VehiclePropertyIds::toString).sorted()
        logInfo("[VEHICLE] speed permission=$speedAllowed; ${visible.size} properties visible: ${names.joinToString()}", tag = Logger.Tags.ADAPTR)
        for ((id, rate) in WATCHED) {
            if (id !in visible || id in subscribed) continue
            runCatching { mgr.getProperty<Any>(id, 0) }.getOrNull()?.let(::take)
            val ok = runCatching { mgr.registerCallback(callback, id, rate) }.getOrDefault(false)
            if (ok) subscribed += id else logWarn("[VEHICLE] could not watch ${VehiclePropertyIds.toString(id)}", tag = Logger.Tags.ADAPTR)
        }
        logInfo("[VEHICLE] ${stateFlow.value}", tag = Logger.Tags.ADAPTR)
    }

    /** One reading, on the main thread. A reading the car marks unavailable or in error clears the field. */
    private fun take(v: CarPropertyValue<*>) {
        val ok = v.status == CarPropertyValue.STATUS_AVAILABLE
        val f = (v.value as? Float)?.takeIf { ok }
        when (v.propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                stateFlow.update { it.copy(speedMps = f) }
                if (f != null) TurnAlert.onCarSpeed(f.toDouble(), SystemClock.elapsedRealtime())
            }
            VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY -> stateFlow.update { it.copy(displayMps = f) }
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> stateFlow.update { it.copy(outsideC = f) }
            VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS -> stateFlow.update { it.copy(speedUnit = (v.value as? Int)?.takeIf { ok }) }
        }
        availableFlow.value = stateFlow.value.available
    }

    /**
     * What is read and watched, at what rate: the speed five times a second (the card's number and the
     * turn alert's timing), the temperature once a second, the unit on change.
     */
    private val WATCHED =
        mapOf(
            VehiclePropertyIds.PERF_VEHICLE_SPEED to CarPropertyManager.SENSOR_RATE_UI,
            VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY to CarPropertyManager.SENSOR_RATE_UI,
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE to CarPropertyManager.SENSOR_RATE_NORMAL,
            VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS to CarPropertyManager.SENSOR_RATE_ONCHANGE,
        )
}
