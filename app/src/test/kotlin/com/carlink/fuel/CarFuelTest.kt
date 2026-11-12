package com.carlink.fuel

import com.carlink.fuel.CarFuel.Kind
import com.carlink.ui.settings.EngineChoice
import com.carlink.ui.settings.FuelGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** INFO_FUEL_TYPE, the engine override and the grade, to the Fuel card's fuel (brief of 11 Nov). */
class CarFuelTest {
    @Test
    fun `diesel types are diesel, exact`() {
        for (t in listOf(3, 4, 5)) {
            val fuel = CarFuel.of(listOf(t), EngineChoice.CAR, FuelGrade.PREMIUM)
            assertEquals(Kind.DIESEL, fuel.kind)
            assertNull("no grade for diesel", fuel.grade)
            assertEquals("DIESEL", fuel.googleType)
            assertEquals("Diesel", fuel.label)
        }
    }

    @Test
    fun `unleaded is gasoline at the driver's grade`() {
        assertEquals(CarFuel(Kind.GASOLINE, FuelGrade.REGULAR), CarFuel.of(listOf(1), EngineChoice.CAR, FuelGrade.REGULAR))
        val premium = CarFuel.of(listOf(2), EngineChoice.CAR, FuelGrade.PREMIUM)
        assertEquals("PREMIUM", premium.googleType)
        assertEquals("Premium gasoline", premium.label)
        assertEquals("MIDGRADE", CarFuel.of(listOf(1), EngineChoice.CAR, FuelGrade.MIDGRADE).googleType)
        assertEquals("REGULAR_UNLEADED", CarFuel.of(listOf(1), EngineChoice.CAR, FuelGrade.REGULAR).googleType)
    }

    @Test
    fun `E85 is a grade only for a car that lists it`() {
        assertEquals(FuelGrade.entries - FuelGrade.E85, CarFuel.grades(listOf(1)))
        assertEquals(FuelGrade.entries, CarFuel.grades(listOf(1, 6)))
        val flex = CarFuel.of(listOf(1, 6), EngineChoice.CAR, FuelGrade.E85)
        assertEquals("E85", flex.googleType)
        assertEquals("E85", flex.label)
        assertEquals("not this car's: regular", FuelGrade.REGULAR, CarFuel.of(listOf(1), EngineChoice.CAR, FuelGrade.E85).grade)
    }

    @Test
    fun `electric alone has range only, and nothing read is unknown`() {
        val ev = CarFuel.of(listOf(10), EngineChoice.CAR, FuelGrade.REGULAR)
        assertEquals(Kind.ELECTRIC, ev.kind)
        assertFalse(ev.hasStations)
        assertNull(ev.googleType)
        assertEquals("a plug-in hybrid fills up with gasoline", Kind.GASOLINE, CarFuel.of(listOf(1, 10), EngineChoice.CAR, FuelGrade.REGULAR).kind)
        for (types in listOf(null, emptyList(), listOf(7))) assertEquals(Kind.UNKNOWN, CarFuel.of(types, EngineChoice.CAR, FuelGrade.REGULAR).kind)
        assertTrue(CarFuel.of(listOf(3), EngineChoice.CAR, FuelGrade.REGULAR).hasStations)
    }

    @Test
    fun `the driver's engine override wins over the car`() {
        assertEquals(Kind.DIESEL, CarFuel.of(listOf(1), EngineChoice.DIESEL, FuelGrade.REGULAR).kind)
        assertEquals(Kind.ELECTRIC, CarFuel.of(listOf(3), EngineChoice.ELECTRIC, FuelGrade.REGULAR).kind)
        assertEquals(CarFuel(Kind.GASOLINE, FuelGrade.PREMIUM), CarFuel.of(null, EngineChoice.PLUG_IN_HYBRID, FuelGrade.PREMIUM))
        assertEquals(Kind.GASOLINE, CarFuel.of(listOf(3), EngineChoice.GASOLINE, FuelGrade.REGULAR).kind)
    }

    @Test
    fun `the grade is stored by its code`() {
        for (g in FuelGrade.entries) assertEquals(g, FuelGrade.of(g.code))
        assertEquals(FuelGrade.REGULAR, FuelGrade.of(null))
        assertEquals(FuelGrade.REGULAR, FuelGrade.of("jet"))
    }
}
