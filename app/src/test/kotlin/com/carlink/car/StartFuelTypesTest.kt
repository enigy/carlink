package com.carlink.car

import com.carlink.ocbm.VehicleConfigSpec
import com.carlink.ocbm.VehicleConfigYaml
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The engine types each start's SUBSCRIBE carries, with no engine picked: the kept report, the same at every connect. */
class StartFuelTypesTest {
    private val unleaded = listOf(1)
    private val diesel = listOf(3)

    private fun yaml(fuelTypes: List<Int>?): ByteArray = VehicleConfigYaml.renderBytes(VehicleConfigSpec(engineTypes = VehicleStatusValues.declaredEngines(emptyList(), fuelTypes)))

    @Test
    fun `with types kept, an early connect and a late one send the same bytes`() {
        val start = StartFuelTypes { unleaded }
        val early = yaml(start.forConnect(live = null)) // the car hasn't reported yet
        val late = yaml(start.forConnect(live = unleaded))
        assertArrayEquals(early, late)
        assertEquals(listOf("gasoline"), VehicleStatusValues.declaredEngines(emptyList(), start.forConnect(null)))
    }

    @Test
    fun `a report that changes mid-start waits for the next start`() {
        var kept: List<Int>? = unleaded
        val start = StartFuelTypes { kept }
        assertEquals(unleaded, start.forConnect(null))
        kept = diesel // the car reported other types, and the store kept them
        assertEquals("this start keeps what its first connect sent", unleaded, start.forConnect(diesel))
        assertEquals("the next start sends the new ones", diesel, StartFuelTypes { kept }.forConnect(null))
    }

    @Test
    fun `a first-ever start, with nothing kept, sends the car's live reading as before`() {
        val start = StartFuelTypes { null }
        assertNull(start.forConnect(null))
        assertEquals(unleaded, start.forConnect(unleaded))
    }
}
