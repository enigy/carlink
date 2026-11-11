package com.carlink.ui.settings

import com.carlink.car.VehicleState
import com.carlink.car.VehicleStatusValues.Shares
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings › Vehicle's words. */
class VehicleTextTest {
    @Test
    fun `each data point reads as the truck shows it`() {
        val s = VehicleState(rangeM = 482_803f, fuelLow = false, outsideC = 16f, fuelTypes = listOf(3))
        assertEquals("300 mi", VehicleText.range(s, imperial = true, permitted = true))
        assertEquals("483 km", VehicleText.range(s, imperial = false, permitted = true))
        assertEquals("61 °F", VehicleText.outside(s, imperial = true))
        assertEquals("16 °C", VehicleText.outside(s, imperial = false))
        assertEquals("Diesel", VehicleText.fuelType(s))
        assertEquals("Fuel isn't low now", VehicleText.lowFuel(s, permitted = true))
        assertEquals(VehicleText.NEEDS_PERMISSION, VehicleText.range(VehicleState(), imperial = true, permitted = false))
        assertEquals(VehicleText.NOT_READ, VehicleText.range(VehicleState(), imperial = true, permitted = true))
        assertEquals(VehicleText.NOT_READ, VehicleText.fuelType(VehicleState(fuelTypes = listOf(7))))
    }

    @Test
    fun `the category line says the GPS and how many points are shared`() {
        assertEquals("Car GPS on · nothing else shared", VehicleText.summary(true, Shares()))
        assertEquals("Car GPS off · 2 shared", VehicleText.summary(false, Shares(range = true, fuelType = true)))
    }
}
