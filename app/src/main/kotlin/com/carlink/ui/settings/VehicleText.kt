package com.carlink.ui.settings

import com.carlink.car.VehicleState
import com.carlink.car.VehicleStatusValues
import kotlin.math.roundToInt

/** Settings › Vehicle's words: each data point's reading as the pane shows it, and the category line. */
internal object VehicleText {
    private const val METRES_PER_KM = 1000f
    private const val METRES_PER_MILE = 1609.344f
    private const val FAHRENHEIT_SCALE = 1.8f
    private const val FAHRENHEIT_OFFSET = 32f

    const val NOT_READ = "Not read from the car yet"
    const val NEEDS_PERMISSION = "Carlink needs the car's energy permission to read it"

    fun gps(
        on: Boolean,
        permitted: Boolean,
    ): String =
        when {
            !on -> "Off: the iPhone uses its own GPS alone"
            !permitted -> "On, but Carlink needs the location permission to read the GPS"
            else -> "The truck's GPS helps the iPhone's own, where it is weak"
        }

    /** Apple's engine names, as the pane says them. */
    fun fuelType(state: VehicleState): String {
        val engines = VehicleStatusValues.engines(state.fuelTypes)
        return if (engines.isEmpty()) NOT_READ else engines.joinToString(" and ") { if (it == "cng") "CNG" else it.replaceFirstChar(Char::uppercase) }
    }

    fun range(
        state: VehicleState,
        imperial: Boolean,
        permitted: Boolean,
    ): String {
        val m = state.rangeM ?: return if (permitted) NOT_READ else NEEDS_PERMISSION
        return if (imperial) "${(m / METRES_PER_MILE).roundToInt()} mi" else "${(m / METRES_PER_KM).roundToInt()} km"
    }

    fun lowFuel(
        state: VehicleState,
        permitted: Boolean,
    ): String {
        val low = state.fuelLow ?: return if (permitted) NOT_READ else NEEDS_PERMISSION
        return if (low) "Fuel is low now" else "Fuel isn't low now"
    }

    fun outside(
        state: VehicleState,
        imperial: Boolean,
    ): String {
        val c = state.outsideC ?: return NOT_READ
        return if (imperial) "${(c * FAHRENHEIT_SCALE + FAHRENHEIT_OFFSET).roundToInt()} °F" else "${c.roundToInt()} °C"
    }

    /** The category list's line: the GPS, and how many data points the iPhone gets. */
    fun summary(
        gps: Boolean,
        shares: VehicleStatusValues.Shares,
    ): String {
        val n = listOf(shares.fuelType, shares.range, shares.lowFuel, shares.outsideTemperature).count { it }
        val shared = if (n == 0) "nothing else shared" else "$n shared"
        return (if (gps) "Car GPS on" else "Car GPS off") + " · " + shared
    }
}
