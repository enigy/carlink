package com.carlink.ui.settings

import com.carlink.car.VehicleStatusValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Settings › Vehicle's engine type selector (Scott, 13 Nov: no "From the car"; the car's own shows until a pick). */
class EngineChoiceTest {
    @Test
    fun `the selector offers the four types and shows the one the car reports`() {
        assertFalse(EngineChoice.CAR in EngineChoice.choices)
        assertEquals(4, EngineChoice.choices.size)

        fun reported(vararg fuelTypes: Int) = EngineChoice.reported(VehicleStatusValues.engines(fuelTypes.toList()))
        assertEquals(EngineChoice.DIESEL, reported(3))
        assertEquals(EngineChoice.GASOLINE, reported(1, 6))
        assertEquals(EngineChoice.ELECTRIC, reported(10))
        assertEquals("either order", EngineChoice.PLUG_IN_HYBRID, reported(10, 1))
        assertNull("nothing read yet", reported())
        assertNull("no type of the four", reported(7))
    }

    @Test
    fun `a pick is remembered by its code and nothing stored follows the car`() {
        for (choice in EngineChoice.choices) assertEquals(choice, EngineChoice.of(choice.code))
        assertEquals(EngineChoice.CAR, EngineChoice.of(null))
    }
}
