package com.carlink.car

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The box's `{"kind":"location"}` records, exactly as ocbm iap2-core `location.rs` writes them. */
class CarGpsFeedRequestTest {
    @Test
    fun `a start names the sentences to build and the phone's intervals`() {
        val start = """{"kind":"location","state":"start","sentences":["GPGGA","GPRMC"],"intervalMs":{"GPRMC":500}}"""
        assertEquals(CarGpsFeed.Request(true, listOf("GPGGA", "GPRMC"), mapOf("GPRMC" to 500L)), CarGpsFeed.Request.of(JSONObject(start)))
        val nothing = """{"kind":"location","state":"start","sentences":[],"intervalMs":{}}"""
        assertEquals(CarGpsFeed.Request(true, emptyList(), emptyMap()), CarGpsFeed.Request.of(JSONObject(nothing)))
    }

    @Test
    fun `Carlink builds only what the head unit hasn't sent lately, and never PASCD`() {
        val wanted = listOf("GPGGA", "GPRMC", "PASCD")
        assertEquals("the emulator writes none", listOf("GPGGA", "GPRMC"), CarGpsFeed.toBuild(wanted, emptyMap(), 10_000L))
        val heard = mapOf("GPGGA" to 9_000L, "PASCD" to 9_500L)
        assertEquals("the head unit's own GGA stands", listOf("GPRMC"), CarGpsFeed.toBuild(wanted, heard, 10_000L))
        assertEquals(
            "until it stops coming",
            listOf("GPGGA", "GPRMC"),
            CarGpsFeed.toBuild(wanted, heard, 9_000L + CarGpsFeed.NATIVE_FRESH_MS + 1),
        )
        assertEquals(emptyList<String>(), CarGpsFeed.toBuild(listOf("PASCD"), emptyMap(), 0L))
    }

    @Test
    fun `a stop is a stop, and anything else isn't a request`() {
        assertEquals(CarGpsFeed.Request(false, emptyList(), emptyMap()), CarGpsFeed.Request.of(JSONObject("""{"kind":"location","state":"stop"}""")))
        assertNull(CarGpsFeed.Request.of(JSONObject("""{"kind":"location","state":"pause"}""")))
        assertNull(CarGpsFeed.Request.of(JSONObject("""{"kind":"nowPlaying","state":"start"}""")))
    }
}
