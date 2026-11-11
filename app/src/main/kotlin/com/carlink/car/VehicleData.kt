package com.carlink.car

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.navigation.TurnAlert

/**
 * The truck's own speed and outside temperature, from the car's property service: the pane's clock
 * card shows them ([VehicleReadings]), and the cluster's turn alert times its flashing on the speed
 * ([TurnAlert.onCarSpeed]). Touches `android.car`: use it only behind a FEATURE_AUTOMOTIVE check.
 *
 * Permissions (AOSP car service manifest, android12L-release): `CAR_SPEED` is dangerous, in the
 * Location group, asked for at start-up by MainActivity; `CAR_EXTERIOR_ENVIRONMENT` and
 * `READ_CAR_DISPLAY_UNITS` are normal. Whether a car serves them to a third-party app is the maker's
 * call and published nowhere; the truck does (1.06 probe, owner's drive 10-26: speed live 0-22 mph,
 * outside 16.5 °C, mph and miles). The properties this app can see are still logged at each connect,
 * in case another car differs. A reading is null until it comes, and stays null where the car says no.
 *
 * Settings › Vehicle adds range and the low-fuel warning (`CAR_ENERGY`, dangerous, asked for there) and the fuel
 * types (`CAR_INFO`, normal), all granted to an app on the truck (the 10 Nov probe drive), for the phone.
 */
object VehicleData {
    /** The one runtime permission: `CAR_SPEED` is dangerous. */
    const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"

    /**
     * Range and the low-fuel warning, for Settings › Vehicle: `CAR_ENERGY` is dangerous too, asked for there when the
     * driver shares either. The truck grants it to an app (the 10 Nov probe drive).
     */
    const val ENERGY_PERMISSION = "android.car.permission.CAR_ENERGY"

    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private val subscribed = mutableSetOf<Int>()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The low-fuel warning, read again every minute while it is readable. On the Mac's emulator (11 Nov) its change
     * events never arrived: the car service had no listener for it though the value read at connect, and Carlink logged
     * no failed watch. A warning that comes on mid-drive must still reach the phone, and a car that never sends the
     * event costs one read a minute.
     */
    private val recheckFuelLow =
        object : Runnable {
            override fun run() {
                val mgr = props ?: return
                runCatching { mgr.getProperty<Any>(VehiclePropertyIds.FUEL_LEVEL_LOW, 0) }.getOrNull()?.let(::take)
                handler.postDelayed(this, FUEL_LOW_RECHECK_MS)
            }
        }

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
                if (ready) connect(c, context) else onCarLost()
            }
        }.onSuccess { car = it }
            .onFailure { logWarn("[VEHICLE] Car.createCar failed: ${it.message}", tag = Logger.Tags.ADAPTR) }
    }

    fun stop() {
        logInfo("[VEHICLE] stopped (watching ${names(subscribed)})", tag = Logger.Tags.ADAPTR)
        handler.removeCallbacks(recheckFuelLow)
        runCatching { props?.unregisterCallback(callback) }
        props = null
        subscribed.clear()
        runCatching { car?.disconnect() }
        car = null
        VehicleReadings.clear()
    }

    /** The car service went away: its managers go with it, so the next connect watches everything again. */
    private fun onCarLost() {
        logWarn("[VEHICLE] car service disconnected", tag = Logger.Tags.ADAPTR)
        handler.removeCallbacks(recheckFuelLow)
        props = null
        subscribed.clear()
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
        val added = mutableListOf<Int>()
        for ((id, rate) in WATCHED) {
            if (id !in visible || id in subscribed) continue
            runCatching { mgr.getProperty<Any>(id, 0) }.getOrNull()?.let(::take)
            val ok = runCatching { mgr.registerCallback(callback, id, rate) }.getOrDefault(false)
            if (ok) added += id else logWarn("[VEHICLE] could not watch ${VehiclePropertyIds.toString(id)}", tag = Logger.Tags.ADAPTR)
        }
        subscribed += added
        logInfo(
            "[VEHICLE] watching ${names(subscribed)} (new: ${names(added)}; not readable: ${names(WATCHED.keys - visible.toSet())})",
            tag = Logger.Tags.ADAPTR,
        )
        handler.removeCallbacks(recheckFuelLow)
        if (VehiclePropertyIds.FUEL_LEVEL_LOW in visible) handler.postDelayed(recheckFuelLow, FUEL_LOW_RECHECK_MS)
        // Static, so read once rather than watched: what the car runs on.
        for (id in READ_ONCE) {
            if (id in visible) runCatching { mgr.getProperty<Any>(id, 0) }.getOrNull()?.let(::take)
        }
        logInfo("[VEHICLE] ${VehicleReadings.state.value}", tag = Logger.Tags.ADAPTR)
    }

    /** One reading, on the main thread. A reading the car marks unavailable or in error clears the field. */
    private fun take(v: CarPropertyValue<*>) {
        val ok = v.status == CarPropertyValue.STATUS_AVAILABLE
        val f = (v.value as? Float)?.takeIf { ok }
        when (v.propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                VehicleReadings.update { it.copy(speedMps = f) }
                if (f != null) TurnAlert.onCarSpeed(f.toDouble(), SystemClock.elapsedRealtime())
            }
            VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY -> VehicleReadings.update { it.copy(displayMps = f) }
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> VehicleReadings.update { it.copy(outsideC = f) }
            VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS -> VehicleReadings.update { it.copy(speedUnit = (v.value as? Int)?.takeIf { ok }) }
            VehiclePropertyIds.RANGE_REMAINING -> VehicleReadings.update { it.copy(rangeM = f) }
            VehiclePropertyIds.FUEL_LEVEL_LOW -> {
                val low = (v.value as? Boolean)?.takeIf { ok }
                if (low != VehicleReadings.state.value.fuelLow) logInfo("[VEHICLE] low fuel warning: ${low ?: "unknown"}", tag = Logger.Tags.ADAPTR)
                VehicleReadings.update { it.copy(fuelLow = low) }
            }
            VehiclePropertyIds.INFO_FUEL_TYPE -> {
                val types = (v.value as? Array<*>)?.mapNotNull { it as? Int }
                VehicleReadings.update { it.copy(fuelTypes = types?.takeIf { ok }) }
            }
        }
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
            // Settings › Vehicle, for the phone: range as it drops, and the low-fuel warning when it turns on or off.
            VehiclePropertyIds.RANGE_REMAINING to CarPropertyManager.SENSOR_RATE_NORMAL,
            VehiclePropertyIds.FUEL_LEVEL_LOW to CarPropertyManager.SENSOR_RATE_ONCHANGE,
        )

    /** Static properties, read at each connect: the fuel types (the truck: [3], diesel). */
    private val READ_ONCE = listOf(VehiclePropertyIds.INFO_FUEL_TYPE)

    private const val FUEL_LOW_RECHECK_MS = 60_000L

    private fun names(ids: Collection<Int>): String = ids.joinToString { VehiclePropertyIds.toString(it) }.ifEmpty { "none" }
}
