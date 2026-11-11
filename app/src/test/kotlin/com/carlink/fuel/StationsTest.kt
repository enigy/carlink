package com.carlink.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Distances, reach and the directions link (the brief of 11 Nov). Every position here is made up. */
class StationsTest {
    private val sea = GeoPoint(-33.9, 151.2)

    @Test
    fun `distance is the great circle`() {
        assertEquals(0.0, Geo.metres(sea, sea), 1e-6)
        assertEquals("a degree of latitude", 111_195.0, Geo.metres(GeoPoint(10.0, 20.0), GeoPoint(11.0, 20.0)), 50.0)
    }

    @Test
    fun `a station is out of reach when 1_3 times its straight line passes the range`() {
        assertFalse(Geo.outOfReach(10_000.0, 13_000f))
        assertTrue(Geo.outOfReach(10_001.0, 13_000f))
        assertFalse("no range read: it reaches", Geo.outOfReach(1e9, null))
    }

    @Test
    fun `the directions link keeps a dot in every locale and fits the box`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("maps:?daddr=-33.900000,151.200000&dirflg=d", Directions.url(sea))
            val far = Directions.url(GeoPoint(-89.999999, -179.999999))
            assertEquals("maps:?daddr=-89.999999,-179.999999&dirflg=d", far)
            assertTrue(far.toByteArray(Charsets.UTF_8).size <= Directions.MAX_BYTES)
        } finally {
            Locale.setDefault(saved)
        }
    }
}
