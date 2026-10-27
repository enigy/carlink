package com.carlink.car

import com.carlink.navigation.GuidanceText
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The truck's readings ([VehicleData]): null where none has come or the car refused. [displayMps] is
 * the speed the speedometer shows, which may read a little above [speedMps]; [speedUnit] is the car's
 * own speed unit (VehicleUnit: [MPH] or [KPH]).
 */
data class VehicleState(
    val speedMps: Float? = null,
    val displayMps: Float? = null,
    val outsideC: Float? = null,
    val speedUnit: Int? = null,
) {
    val available: Boolean get() = speedMps != null || displayMps != null || outsideC != null

    /** Miles and °F, or kilometres and °C: the truck's own speed unit, else the locale's. */
    fun imperial(locale: Locale): Boolean =
        when (speedUnit) {
            MPH -> true
            KPH -> false
            else -> GuidanceText.imperial(locale)
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

        private const val MPS_PER_MPH = 0.44704f
        private const val MPS_PER_KPH = 1 / 3.6f
        private const val FAHRENHEIT_SCALE = 1.8f
        private const val FAHRENHEIT_OFFSET = 32f
    }
}
