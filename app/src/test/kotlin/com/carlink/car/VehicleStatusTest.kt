package com.carlink.car

import com.carlink.car.VehicleStatusValues.Shares
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Vehicle's data points for the phone, in Apple's terms (OCBM `iap2_core::vehicle` takes them). */
class VehicleStatusTest {
    @Test
    fun `the truck's diesel is Apple's diesel, and a fuel Apple has no name for is left out`() {
        assertEquals(listOf("diesel"), VehicleStatusValues.engines(listOf(3)))
        assertEquals(listOf("gasoline", "electric"), VehicleStatusValues.engines(listOf(1, 10, 2)))
        assertEquals("LPG", emptyList<String>(), VehicleStatusValues.engines(listOf(7)))
        assertEquals(emptyList<String>(), VehicleStatusValues.engines(null))
    }

    @Test
    fun `values go in Apple's types - range in km, the temperature as int16, the warning one byte`() {
        val state = VehicleState(rangeM = 512_400f, outsideC = -6.6f, fuelLow = true)
        val all = setOf(VehicleStatusValues.RANGE, VehicleStatusValues.OUTSIDE_TEMPERATURE, VehicleStatusValues.RANGE_WARNING)
        assertArrayEquals(byteArrayOf(3, 0x02, 0x00, 4, 0xFF.toByte(), 0xF9.toByte(), 6, 1), VehicleStatusValues.encode(state, all))
        assertArrayEquals("what hasn't been read is left out", byteArrayOf(4, 0, 16), VehicleStatusValues.encode(VehicleState(outsideC = 16.2f), all))
        assertEquals(0, VehicleStatusValues.encode(state, emptySet()).size)
    }

    @Test
    fun `what is declared follows the shares`() {
        assertEquals(emptyList<String>(), VehicleStatusValues.declared(Shares()))
        val everything = Shares(fuelType = true, range = true, lowFuel = true, outsideTemperature = true)
        assertEquals(listOf("range", "outsideTemperature", "rangeWarning"), VehicleStatusValues.declared(everything))
        assertEquals(setOf(VehicleStatusValues.RANGE, VehicleStatusValues.RANGE_WARNING), Shares(range = true, lowFuel = true).ids)
    }

    private val sent = mutableListOf<List<Byte>>()
    private val shares = MutableStateFlow(Shares(range = true, outsideTemperature = true))
    private val feed = VehicleStatusFeed(CoroutineScope(Dispatchers.Unconfined), shares) { values -> sent.add(values.toList()) }

    @After
    fun clear() {
        feed.stop()
        VehicleReadings.clear()
    }

    @Test
    fun `once the phone asks, readings go at once and at each real change, until it stops`() {
        VehicleReadings.update { VehicleState(rangeM = 100_000f, outsideC = 10f, speedMps = 3f) }
        feed.onRecord(JSONObject("""{"kind":"vehicleStatus","state":"start","want":[3,4,6]}"""))
        assertEquals(listOf(listOf<Byte>(3, 0, 100, 4, 0, 10)), sent)
        VehicleReadings.update { it.copy(speedMps = 5f, rangeM = 100_200f) }
        assertEquals("speed isn't shared, and 100.2 km is still 100: nothing sent", 1, sent.size)
        VehicleReadings.update { it.copy(rangeM = 99_000f) }
        assertEquals(listOf<Byte>(3, 0, 99, 4, 0, 10), sent.last())
        shares.value = Shares(range = true)
        assertEquals("the driver stopped sharing the temperature", listOf<Byte>(3, 0, 99), sent.last())
        feed.onRecord(JSONObject("""{"kind":"vehicleStatus","state":"stop"}"""))
        VehicleReadings.update { it.copy(rangeM = 50_000f) }
        assertEquals(3, sent.size)
        assertTrue(feed.wanted.isEmpty())
    }
}
