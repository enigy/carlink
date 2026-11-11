package com.carlink.ui.settings

import com.carlink.car.VehicleState
import com.carlink.car.VehicleStatusValues.Shares
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.DecimalFormatSymbols

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
        assertEquals("Car GPS off · 2 shared", VehicleText.summary(false, Shares(range = true, lowFuel = true)))
    }

    @Test
    fun `the engine type row says what the iPhone gets and where it comes from`() {
        val diesel = VehicleState(fuelTypes = listOf(3))
        assertEquals("Diesel, from the car", VehicleText.engine(EngineChoice.CAR, diesel))
        assertEquals("Your choice (the car reports Diesel)", VehicleText.engine(EngineChoice.GASOLINE, diesel))
        assertEquals("Your choice (the car reports none yet)", VehicleText.engine(EngineChoice.ELECTRIC, VehicleState()))
        assertTrue(VehicleText.engine(EngineChoice.CAR, VehicleState()).contains("Gasoline, the adapter's default"))
    }

    @Test
    fun `the Fuel card shows the range large and the tank in the car's units`() {
        val truck = VehicleState(rangeM = 502_000f, fuelLevelMl = 88_200f, fuelCapacityMl = 90_750f)
        assertEquals(312 to "mi", VehicleText.rangeFigure(truck, imperial = true))
        assertEquals(502 to "km", VehicleText.rangeFigure(truck, imperial = false))
        assertEquals(null, VehicleText.rangeFigure(VehicleState(), imperial = true))
        assertEquals("88 of 91 L", VehicleText.tank(truck, imperial = false))
        val point = DecimalFormatSymbols.getInstance().decimalSeparator
        assertEquals("23${point}3 of 24${point}0 gal", VehicleText.tank(truck, imperial = true))
        assertEquals(null, VehicleText.tank(VehicleState(fuelLevelMl = 1f), imperial = true))
    }

    @Test
    fun `the engine choice is stored by its code`() {
        for (c in EngineChoice.entries) assertEquals(c, EngineChoice.of(c.code))
        assertEquals("unknown or missing: the car's", EngineChoice.CAR, EngineChoice.of("rocket"))
        assertEquals(EngineChoice.CAR, EngineChoice.of(null))
        assertEquals(listOf("gasoline", "electric"), EngineChoice.PLUG_IN_HYBRID.engines)
    }
}
