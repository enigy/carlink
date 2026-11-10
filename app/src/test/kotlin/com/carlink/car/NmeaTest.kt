package com.carlink.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The head unit's GPS fix as the NMEA the phone reads (LIVI item 4): exact text, checksums, and every edge. */
class NmeaTest {
    /** 10 Nov 2026 17:28:14.25 UTC, Southfield MI: the geoid sits 34 m below the ellipsoid there. */
    private val fix =
        Nmea.Fix(
            utcMs = 1_794_331_694_250L,
            latitude = 42.4725,
            longitude = -83.1485,
            ellipsoidAltitudeM = 156.2,
            mslAltitudeM = 190.2,
            speedMps = 13.9f,
            bearingDeg = 271.5f,
            accuracyM = 4.5f,
            satellites = 9,
        )

    @Test
    fun `a fix becomes GGA and RMC with their checksums`() {
        assertEquals("\$GPGGA,172814.25,4228.35000,N,08308.91000,W,1,09,0.9,190.2,M,-34.0,M,,*51", Nmea.gga(fix))
        assertEquals("\$GPRMC,172814.25,A,4228.35000,N,08308.91000,W,27.02,271.5,101126,,,A*40", Nmea.rmc(fix))
    }

    @Test
    fun `the checksum is NMEA's own, on the textbook sentence`() {
        assertEquals("47", Nmea.checksum("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
    }

    @Test
    fun `south, west, a minute that rounds up, and nothing but a position`() {
        val bare = Nmea.Fix(utcMs = 0L, latitude = -33.99999999, longitude = -151.2093)
        assertEquals("\$GPGGA,000000.00,3400.00000,S,15112.55800,W,1,,,,M,,M,,*74", Nmea.gga(bare))
        assertEquals("\$GPRMC,000000.00,A,3400.00000,S,15112.55800,W,,,010170,,,A*5F", Nmea.rmc(bare))
    }

    @Test
    fun `without Android 14's sea level height the ellipsoid stands in with no separation`() {
        val gga = Nmea.gga(fix.copy(mslAltitudeM = null))
        assertTrue(gga, gga.contains(",156.2,M,0.0,M,,*"))
    }

    @Test
    fun `a decimal comma locale still writes points`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("\$GPRMC,172814.25,A,4228.35000,N,08308.91000,W,27.02,271.5,101126,,,A*40", Nmea.rmc(fix))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `only the sentences asked for, in Apple's order, each short enough for the box`() {
        assertEquals(listOf(Nmea.gga(fix), Nmea.rmc(fix)), Nmea.sentences(fix, listOf("GPRMC", "GPGSV", "GPGGA")))
        assertEquals(listOf(Nmea.rmc(fix)), Nmea.sentences(fix, setOf("GPRMC")))
        assertEquals(emptyList<String>(), Nmea.sentences(fix, setOf("PASCD")))
        val longest = fix.copy(latitude = -89.9999, longitude = -179.9999, satellites = 99, accuracyM = 999f, mslAltitudeM = -9999.9, speedMps = 999f)
        for (s in Nmea.sentences(longest, Nmea.BUILT)) assertTrue("${s.length}: $s", s.length <= BOX_MAX_SENTENCE)
    }

    private companion object {
        /** ocbm iap2-core `location::MAX_SENTENCE`: longer is dropped as malformed. */
        const val BOX_MAX_SENTENCE = 80
    }
}
