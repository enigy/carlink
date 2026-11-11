package com.carlink.car

import com.carlink.navigation.GuidanceText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The truck's latest readings, for the clock card. Plain Kotlin on purpose: [VehicleData], which
 * writes them, touches `android.car`, which exists only on a head unit, so anything that just shows
 * them (the clock card, its tests) reads them here and never loads that code.
 */
object VehicleReadings {
    private val flow = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = flow.asStateFlow()

    internal fun update(change: (VehicleState) -> VehicleState) = flow.update(change)

    internal fun clear() {
        flow.value = VehicleState()
    }
}

/**
 * The truck's readings ([VehicleData]): null where none has come or the car refused. [displayMps] is
 * the speed the speedometer shows, which may read a little above [speedMps]; [speedUnit] is the car's
 * own speed unit (VehicleUnit: [MPH] or [KPH]). [rangeM] (RANGE_REMAINING, metres) and [fuelLow]
 * (FUEL_LEVEL_LOW) need CAR_ENERGY, [fuelTypes] (INFO_FUEL_TYPE, Android's FuelType values) CAR_INFO:
 * what Settings › Vehicle can share with the phone ([VehicleStatusValues]). The Fuel card adds [fuelLevelMl] (FUEL_LEVEL,
 * CAR_ENERGY), [fuelCapacityMl] (INFO_FUEL_CAPACITY, CAR_INFO), both millilitres, and [distanceUnit]
 * (DISTANCE_DISPLAY_UNITS, VehicleUnit: [MILE], [KILOMETER] or [METER]).
 */
data class VehicleState(
    val speedMps: Float? = null,
    val displayMps: Float? = null,
    val outsideC: Float? = null,
    val speedUnit: Int? = null,
    val rangeM: Float? = null,
    val fuelLow: Boolean? = null,
    val fuelTypes: List<Int>? = null,
    val fuelLevelMl: Float? = null,
    val fuelCapacityMl: Float? = null,
    val distanceUnit: Int? = null,
) {
    /** Miles and °F, or kilometres and °C: the truck's own speed unit, else the locale's. */
    fun imperial(locale: Locale): Boolean =
        when (speedUnit) {
            MPH -> true
            KPH -> false
            else -> GuidanceText.imperial(locale)
        }

    /** Miles or kilometres for distances: the car's own distance unit, else its speed unit, else the locale's. */
    fun distanceImperial(locale: Locale): Boolean =
        when (distanceUnit) {
            MILE -> true
            KILOMETER, METER -> false
            else -> imperial(locale)
        }

    /** How full the tank is, 0 to 1: only when both the level and the tank's size are read. */
    fun fuelFraction(): Float? {
        val level = fuelLevelMl ?: return null
        val capacity = fuelCapacityMl?.takeIf { it > 0f } ?: return null
        return (level / capacity).coerceIn(0f, 1f)
    }

    /** The speedometer's figure in whole mph or km/h; reversing reads as a positive speed. */
    fun speed(imperial: Boolean): Int? = (displayMps ?: speedMps)?.let { abs(it) / if (imperial) MPS_PER_MPH else MPS_PER_KPH }?.roundToInt()

    /** The outside temperature in whole °F or °C. */
    fun outside(imperial: Boolean): Int? = outsideC?.let { if (imperial) it * FAHRENHEIT_SCALE + FAHRENHEIT_OFFSET else it }?.roundToInt()

    companion object {
        /** VehicleUnit.MILES_PER_HOUR: the truck's 144 on the 10-26 drive. */
        const val MPH = 0x90

        /** VehicleUnit.KILOMETERS_PER_HOUR. */
        const val KPH = 0x91

        /** VehicleUnit.METER, KILOMETER and MILE: DISTANCE_DISPLAY_UNITS' values. */
        const val METER = 0x21
        const val KILOMETER = 0x23
        const val MILE = 0x24

        private const val MPS_PER_MPH = 0.44704f
        private const val MPS_PER_KPH = 1 / 3.6f
        private const val FAHRENHEIT_SCALE = 1.8f
        private const val FAHRENHEIT_OFFSET = 32f
    }
}
