package com.carlink.fuel

import com.carlink.car.VehicleState
import kotlin.math.roundToInt

/**
 * The Fuel card's low fuel: the car's own warning (FUEL_LEVEL_LOW), or the range at or under the driver's threshold,
 * Settings › Vehicle › Low fuel warning at (Scott, 11 Nov: range, default 25, in the car's distance unit, miles or km).
 * The car's warning can't be moved, so the threshold only ever warns earlier. It drives the card's banner and the pane's
 * turn to the card; what the iPhone hears stays the car's own warning. Plain Kotlin, so it is tested without a car.
 */
object LowFuel {
    const val DEFAULT_AT = 25

    /** The thresholds Settings › Vehicle offers, in the car's distance unit. */
    val CHOICES = listOf(10, 25, 50, 75, 100)

    private const val METRES_PER_MILE = 1609.344f
    private const val METRES_PER_KM = 1000f

    /**
     * Low now: the car warns, or its range ([VehicleState.rangeM]) is at or under [at] miles ([imperial]) or kilometres,
     * counted in the whole units the card shows, so a card that reads "25 mi" is low at 25.
     */
    fun isLow(
        state: VehicleState,
        at: Int,
        imperial: Boolean,
    ): Boolean {
        if (state.fuelLow == true) return true
        val m = state.rangeM ?: return false
        return (m / (if (imperial) METRES_PER_MILE else METRES_PER_KM)).roundToInt() <= at
    }
}
