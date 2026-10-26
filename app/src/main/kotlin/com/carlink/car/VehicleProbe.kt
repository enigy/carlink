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

/**
 * TEST BUILD probe (owner, 10-26): which vehicle values GM's head unit lets this app read, ahead of a
 * Vehicle card on the pane. It only logs; nothing shows on screen.
 *
 * Google's levels (AOSP car service manifest, android12L-release): `CAR_EXTERIOR_ENVIRONMENT`
 * (outside temperature) and `READ_CAR_DISPLAY_UNITS` (the truck's mph / miles setting) are normal,
 * granted at install; `CAR_SPEED` is dangerous, in the Location group, and asked for at start-up by
 * MainActivity. Whether the VHAL serves a property to a third-party app is GM's call and published
 * nowhere, so this asks the car: every property the app can see, a first read of each one a Vehicle
 * card would use ([TARGETS]), then speed and outside temperature as they change, at most one line per
 * [SPEED_LOG_MS] and [TEMP_LOG_MS]. [start] again once the speed prompt is answered: the list and the
 * reads are logged afresh, and a property that became readable is subscribed.
 */
class VehicleProbe(
    private val context: Context,
) {
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private val subscribed = mutableSetOf<Int>()
    private val lastLogged = mutableMapOf<Int, Long>()

    private val callback =
        object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) = onChange(value)

            override fun onErrorEvent(
                propId: Int,
                areaId: Int,
            ) {
                logWarn("[VEHICLE] ${name(propId)} error event (area $areaId)", tag = Logger.Tags.ADAPTR)
            }
        }

    /** Idempotent; a no-op off automotive. While the car service is still connecting, its ready callback probes. */
    fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        val existing = car
        if (existing != null) {
            if (existing.isConnected) probe(existing)
            return
        }
        runCatching {
            Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (ready) probe(c) else logWarn("[VEHICLE] car service disconnected", tag = Logger.Tags.ADAPTR)
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
    }

    private fun probe(c: Car) {
        val mgr = runCatching { c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }.getOrNull()
        if (mgr == null) {
            logWarn("[VEHICLE] CarPropertyManager unavailable", tag = Logger.Tags.ADAPTR)
            return
        }
        props = mgr
        val granted = PERMISSIONS.joinToString { "${it.substringAfterLast('.')}=${context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED}" }
        logInfo("[VEHICLE] permissions: $granted", tag = Logger.Tags.ADAPTR)
        val visible =
            runCatching { mgr.propertyList.map { it.propertyId } }
                .onFailure { logWarn("[VEHICLE] property list failed: ${it.javaClass.simpleName}: ${it.message}", tag = Logger.Tags.ADAPTR) }
                .getOrDefault(emptyList())
        logInfo("[VEHICLE] ${visible.size} properties visible to this app:", tag = Logger.Tags.ADAPTR)
        val names = visible.map(::name).sorted()
        for (line in names.chunked(LIST_CHUNK)) logInfo("[VEHICLE]   ${line.joinToString()}", tag = Logger.Tags.ADAPTR)
        for (id in TARGETS) {
            val read = runCatching { mgr.getProperty<Any>(id, 0) }
            val got = read.fold({ describe(it) }, { "${it.javaClass.simpleName}: ${it.message}" })
            logInfo("[VEHICLE] ${name(id)} (${if (id in visible) "listed" else "not listed"}): $got", tag = Logger.Tags.ADAPTR)
        }
        for (id in WATCHED) {
            if (id !in visible || id in subscribed) continue
            val ok = runCatching { mgr.registerCallback(callback, id, CarPropertyManager.SENSOR_RATE_NORMAL) }.getOrDefault(false)
            if (ok) subscribed += id
            logInfo("[VEHICLE] watching ${name(id)}: $ok", tag = Logger.Tags.ADAPTR)
        }
    }

    private fun onChange(value: CarPropertyValue<*>) {
        val now = SystemClock.elapsedRealtime()
        val every = if (value.propertyId == VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE) TEMP_LOG_MS else SPEED_LOG_MS
        val last = lastLogged[value.propertyId]
        if (last != null && now - last < every) return
        lastLogged[value.propertyId] = now
        logInfo("[VEHICLE] ${name(value.propertyId)} = ${describe(value)}", tag = Logger.Tags.ADAPTR)
    }

    private fun describe(v: CarPropertyValue<*>?): String {
        if (v == null) return "null"
        val f = v.value as? Float
        val shown =
            when {
                f == null -> "${v.value}"
                v.propertyId == VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> "%.1f °C (%.0f °F)".format(f, f * FAHRENHEIT_SCALE + FAHRENHEIT_OFFSET)
                else -> "%.2f m/s (%.0f mph)".format(f, f * MPH_PER_MPS)
            }
        return "$shown, status ${v.status}"
    }

    private fun name(id: Int): String = VehiclePropertyIds.toString(id)

    companion object {
        /** The one runtime permission: `CAR_SPEED` is dangerous. */
        const val SPEED = "android.car.permission.CAR_SPEED"

        /** What the probe declares (AndroidManifest): speed, outside temperature, the display units. */
        val PERMISSIONS = listOf(SPEED, "android.car.permission.CAR_EXTERIOR_ENVIRONMENT", "android.car.permission.READ_CAR_DISPLAY_UNITS")

        /** Read once per probe: what a Vehicle card would show, and the units to show it in. */
        private val TARGETS =
            listOf(
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY,
                VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE,
                VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS,
                VehiclePropertyIds.DISTANCE_DISPLAY_UNITS,
            )

        /** Logged through the drive as they change. */
        private val WATCHED = listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE)

        private const val SPEED_LOG_MS = 10_000L
        private const val TEMP_LOG_MS = 60_000L
        private const val LIST_CHUNK = 20
        private const val MPH_PER_MPS = 2.23694f
        private const val FAHRENHEIT_SCALE = 1.8f
        private const val FAHRENHEIT_OFFSET = 32f
    }
}
