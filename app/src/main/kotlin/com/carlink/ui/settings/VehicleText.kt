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
    private const val ML_PER_LITRE = 1000f
    private const val ML_PER_US_GALLON = 3785.41f

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

    /** The Fuel grade row's note: the car reports unleaded without a grade, or the driver set gasoline in Engine type. */
    fun gradeNote(engine: EngineChoice): String =
        if (engine == EngineChoice.CAR) "The car says unleaded but not which grade" else "Which gasoline the truck takes, for the Engine type you set"

    /** The engine type row: what the iPhone gets, and where it comes from. */
    fun engine(
        choice: EngineChoice,
        state: VehicleState,
    ): String {
        val car = fuelType(state)
        return when {
            choice != EngineChoice.CAR -> "Your choice (the car reports ${if (car == NOT_READ) "none yet" else car})"
            car == NOT_READ -> "Not read from the car yet: the iPhone hears Gasoline, the adapter's default"
            else -> "$car, from the car"
        }
    }

    fun range(
        state: VehicleState,
        imperial: Boolean,
        permitted: Boolean,
    ): String {
        val m = state.rangeM ?: return if (permitted) NOT_READ else NEEDS_PERMISSION
        return if (imperial) "${(m / METRES_PER_MILE).roundToInt()} mi" else "${(m / METRES_PER_KM).roundToInt()} km"
    }

    /** A search radius in the car's unit: "5 mi", or "8 km". */
    fun radius(
        miles: Int,
        imperial: Boolean,
    ): String = if (imperial) "$miles mi" else "${(miles * METRES_PER_MILE / METRES_PER_KM).roundToInt()} km"

    /** The range as the Fuel card shows it large: its whole number and unit, (312, "mi"); null until it is read. */
    fun rangeFigure(
        state: VehicleState,
        imperial: Boolean,
    ): Pair<Int, String>? {
        val m = state.rangeM ?: return null
        return if (imperial) (m / METRES_PER_MILE).roundToInt() to "mi" else (m / METRES_PER_KM).roundToInt() to "km"
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

    /** The tank for the Fuel card's small print: "88 of 91 L", or "23.3 of 24.0 gal". Null unless both are read. */
    fun tank(
        state: VehicleState,
        imperial: Boolean,
    ): String? {
        val level = state.fuelLevelMl ?: return null
        val size = state.fuelCapacityMl?.takeIf { it > 0f } ?: return null
        return if (imperial) {
            "%.1f of %.1f gal".format(level / ML_PER_US_GALLON, size / ML_PER_US_GALLON)
        } else {
            "${(level / ML_PER_LITRE).roundToInt()} of ${(size / ML_PER_LITRE).roundToInt()} L"
        }
    }

    /** The category list's line: the GPS, and how many data points the iPhone gets. */
    fun summary(
        gps: Boolean,
        shares: VehicleStatusValues.Shares,
    ): String {
        val n = listOf(shares.range, shares.lowFuel, shares.outsideTemperature).count { it }
        val shared = if (n == 0) "nothing else shared" else "$n shared"
        return (if (gps) "Car GPS on" else "Car GPS off") + " · " + shared
    }
}
