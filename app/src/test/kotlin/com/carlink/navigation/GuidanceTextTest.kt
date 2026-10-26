package com.carlink.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.util.Locale

class GuidanceTextTest {
    @Test
    fun `feet to fifty, then miles with one decimal, none from ten`() {
        assertEquals("300 ft", GuidanceText.distance(91, imperial = true).toString())
        assertEquals("the floor is fifty", "50 ft", GuidanceText.distance(3, imperial = true).toString())
        assertEquals("0.4 mi", GuidanceText.distance(640, imperial = true).toString())
        assertEquals("14 mi", GuidanceText.distance(22_531, imperial = true).toString())
    }

    @Test
    fun `metres to fifty, then kilometres`() {
        assertEquals("450 m", GuidanceText.distance(462, imperial = false).toString())
        assertEquals("1.5 km", GuidanceText.distance(1_480, imperial = false).toString())
        assertEquals("23 km", GuidanceText.distance(23_400, imperial = false).toString())
    }

    @Test
    fun `the value and the unit come apart for the card`() {
        val d = GuidanceText.distance(640, imperial = true)
        assertEquals("0.4", d.value)
        assertEquals("mi", d.unit)
    }

    @Test
    fun `imperial by country`() {
        assertTrue(GuidanceText.imperial(Locale.US))
        assertTrue(GuidanceText.imperial(Locale.UK))
        assertFalse(GuidanceText.imperial(Locale.GERMANY))
        assertFalse("no country at all", GuidanceText.imperial(Locale.ROOT))
    }

    @Test
    fun `time to go in minutes and hours`() {
        assertEquals("< 1 min", GuidanceText.duration(20))
        assertEquals("23 min", GuidanceText.duration(23 * 60 + 10))
        assertEquals("1 h", GuidanceText.duration(3_600))
        assertEquals("1 h 5 min", GuidanceText.duration(3_900))
    }

    @Test
    fun `arrival in the head unit's clock style`() {
        val now = LocalTime.of(15, 49)
        assertEquals("4:12", GuidanceText.arrival(23 * 60, now, twentyFourHour = false))
        assertEquals("16:12", GuidanceText.arrival(23 * 60, now, twentyFourHour = true))
    }
}
