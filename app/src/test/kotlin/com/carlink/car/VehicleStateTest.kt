package com.carlink.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VehicleStateTest {
    @Test
    fun `the truck's own unit wins over the locale`() {
        assertTrue(VehicleState(speedUnit = VehicleState.MPH).imperial(Locale.GERMANY))
        assertFalse(VehicleState(speedUnit = VehicleState.KPH).imperial(Locale.US))
        assertTrue("no unit read: the locale's", VehicleState().imperial(Locale.US))
    }

    @Test
    fun `speed reads as the speedometer shows it, in whole units`() {
        // The 10-26 drive: 8.59 m/s logged as 19 mph.
        assertEquals(19, VehicleState(speedMps = 8.59f).speed(imperial = true))
        assertEquals(31, VehicleState(speedMps = 8.59f).speed(imperial = false))
        assertEquals("the speedometer's figure first", 20, VehicleState(speedMps = 8.59f, displayMps = 8.94f).speed(imperial = true))
        assertEquals("reversing", 3, VehicleState(speedMps = -1.5f).speed(imperial = true))
        assertNull(VehicleState(outsideC = 16.5f).speed(imperial = true))
    }

    @Test
    fun `outside temperature in whole degrees`() {
        // The 10-26 drive: 16.5 °C, 62 °F.
        assertEquals(62, VehicleState(outsideC = 16.5f).outside(imperial = true))
        assertEquals(17, VehicleState(outsideC = 16.5f).outside(imperial = false))
        assertNull(VehicleState(speedMps = 1f).outside(imperial = true))
    }

    @Test
    fun `the Fuel card's distances follow the car's distance unit, then its speed unit`() {
        assertTrue(VehicleState(distanceUnit = VehicleState.MILE, speedUnit = VehicleState.KPH).distanceImperial(Locale.GERMANY))
        assertFalse(VehicleState(distanceUnit = VehicleState.KILOMETER).distanceImperial(Locale.US))
        assertFalse(VehicleState(distanceUnit = VehicleState.METER).distanceImperial(Locale.US))
        assertTrue("no distance unit: the speed unit", VehicleState(speedUnit = VehicleState.MPH).distanceImperial(Locale.GERMANY))
    }

    @Test
    fun `the tank's bar needs both the level and the tank's size`() {
        // The 10 Nov probe drive: 88.2 L of 90.75 L.
        assertEquals(0.972f, VehicleState(fuelLevelMl = 88_200f, fuelCapacityMl = 90_750f).fuelFraction()!!, 0.001f)
        assertNull(VehicleState(fuelLevelMl = 88_200f).fuelFraction())
        assertNull(VehicleState(fuelCapacityMl = 90_750f).fuelFraction())
        assertNull("a tank of no size", VehicleState(fuelLevelMl = 1f, fuelCapacityMl = 0f).fuelFraction())
        assertEquals("a level past the tank's size is full", 1f, VehicleState(fuelLevelMl = 95_000f, fuelCapacityMl = 90_750f).fuelFraction()!!, 0f)
    }
}
