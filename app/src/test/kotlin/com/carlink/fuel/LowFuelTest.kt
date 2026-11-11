package com.carlink.fuel

import com.carlink.car.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scott, 11 Nov: the low fuel warning's threshold is range, default 25, in the car's distance unit. */
class LowFuelTest {
    private val mile = 1609.344f

    @Test
    fun `the car's own warning always counts`() {
        assertTrue(LowFuel.isLow(VehicleState(fuelLow = true, rangeM = 400 * mile), LowFuel.DEFAULT_AT, imperial = true))
        assertFalse(LowFuel.isLow(VehicleState(fuelLow = false), LowFuel.DEFAULT_AT, imperial = true))
        assertFalse("nothing read", LowFuel.isLow(VehicleState(), LowFuel.DEFAULT_AT, imperial = true))
    }

    @Test
    fun `range at or under the threshold is low, in the car's unit`() {
        assertEquals(25, LowFuel.DEFAULT_AT)
        assertTrue(LowFuel.DEFAULT_AT in LowFuel.CHOICES)
        assertTrue(LowFuel.isLow(VehicleState(rangeM = 24 * mile), 25, imperial = true))
        assertTrue("at it", LowFuel.isLow(VehicleState(rangeM = 25 * mile), 25, imperial = true))
        assertFalse(LowFuel.isLow(VehicleState(rangeM = 26 * mile), 25, imperial = true))
        assertTrue("a car set to km: 25 km", LowFuel.isLow(VehicleState(rangeM = 24_000f), 25, imperial = false))
        assertFalse("40 km is 25 mi, not 25 km", LowFuel.isLow(VehicleState(rangeM = 40_000f), 25, imperial = false))
        assertTrue("a higher threshold warns earlier", LowFuel.isLow(VehicleState(rangeM = 70 * mile), 75, imperial = true))
    }
}
