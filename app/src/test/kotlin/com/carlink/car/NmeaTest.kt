package com.carlink.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `no satellite count, or Android's 0, leaves the field empty`() {
        val zero = Nmea.gga(fix.copy(satellites = 0))
        assertTrue(zero, zero.contains(",W,1,,0.9,"))
        assertEquals(zero, Nmea.gga(fix.copy(satellites = null)))
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
    fun `only the sentences asked for, in Apple's order, each inside NMEA's own limit`() {
        assertEquals(listOf(Nmea.gga(fix), Nmea.rmc(fix)), Nmea.sentences(fix, listOf("GPRMC", "GPGSV", "GPGGA")))
        assertEquals(listOf(Nmea.rmc(fix)), Nmea.sentences(fix, setOf("GPRMC")))
        assertEquals(emptyList<String>(), Nmea.sentences(fix, setOf("PASCD")))
        val longest = fix.copy(latitude = -89.9999, longitude = -179.9999, satellites = 99, accuracyM = 999f, mslAltitudeM = -9999.9, speedMps = 999f)
        for (s in Nmea.sentences(longest, Nmea.BUILT)) assertTrue("${s.length}: $s", s.length <= NMEA_MAX_SENTENCE)
    }

    @Test
    fun `the head unit's own sentences are named when the box would take them`() {
        // The truck's, from the 10 Nov probe drive: its PASCD runs past NMEA's 80 with six speed samples.
        val pascd = "\$PASCD,68536.594,C,D,0,6,0.10,7.839,0.20,7.956,0.30,8.034,0.40,8.129,0.50,8.294,0.60,8.381*47"
        assertEquals(93, pascd.length)
        assertEquals("PASCD", Nmea.nameOf(pascd))
        // A made-up RMC in the head unit's form: six-decimal minutes, four-decimal speed and course, CR LF.
        assertEquals("GPRMC", Nmea.nameOf("\$GPRMC,000000.00,A,3400.000000,S,15112.558000,W,0.0000,0.0000,010170,,,A*5F\r\n"))
        assertEquals("GPGGA", Nmea.nameOf(Nmea.gga(fix)))
        assertNull("a checksum that doesn't hold", Nmea.nameOf(pascd.replace("*47", "*48")))
        assertNull("no \$", Nmea.nameOf(pascd.removePrefix("\$")))
        assertNull("one checksum digit", Nmea.nameOf(pascd.dropLast(1)))
        assertNull("longer than the box takes", Nmea.nameOf(Nmea.sentence("PASCD," + "0.10,7.839,".repeat(30))))
        assertNull("not printable", Nmea.nameOf(Nmea.sentence("PASCD,\u0001")))
        assertNull("a four-letter name", Nmea.nameOf(Nmea.sentence("PASC,1")))
    }

    private companion object {
        /** NMEA-0183's own limit. What Carlink builds stays inside it; the box takes up to [Nmea.MAX_SENTENCE]. */
        const val NMEA_MAX_SENTENCE = 80
    }
}
