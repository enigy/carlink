package com.carlink.car

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * NMEA-0183 sentences of the head unit's own GPS fix, for the iPhone (LIVI research item 4): the GGA and RMC that
 * Apple's location plane names first (iAP2 LocationInformation, Identify param 22 subs 17 and 18). Pure, so the
 * exact text is unit-tested; [CarGpsFeed] feeds it real fixes. The talker is `GP` because that is the name Apple's
 * selectors use, whatever constellations the fix came from. Numbers are always formatted in [Locale.US]: a comma
 * for a decimal point would make every sentence unreadable.
 */
object Nmea {
    /** The two this build can produce, in Apple's order. */
    val BUILT = listOf("GPGGA", "GPRMC")

    data class Fix(
        val utcMs: Long,
        val latitude: Double,
        val longitude: Double,
        /** Height above the WGS84 ellipsoid (Android's `altitude`), metres, or null. */
        val ellipsoidAltitudeM: Double? = null,
        /** Height above mean sea level (Android 14's `mslAltitudeMeters`), metres, or null. */
        val mslAltitudeM: Double? = null,
        val speedMps: Float? = null,
        /** Degrees clockwise from true north, or null. */
        val bearingDeg: Float? = null,
        /** Android's horizontal accuracy, metres at 68 %, or null. */
        val accuracyM: Float? = null,
        /** Satellites in the fix, or null. Android reports 0 when it doesn't know (the emulator always does), so 0 is written as none. */
        val satellites: Int? = null,
    )

    /**
     * The receiver's assumed range error, metres at one sigma, that turns Android's accuracy back into an HDOP
     * (accuracy ≈ HDOP × this). The phone weighs a fix by its HDOP, and Android reports only the accuracy.
     */
    private const val UERE_M = 5.0
    private const val KNOTS_PER_MPS = 3600.0 / 1852.0

    /** The sentences of [names] this object builds, in Apple's order; the rest are left out. */
    fun sentences(
        fix: Fix,
        names: Collection<String>,
    ): List<String> = BUILT.filter { it in names }.map { if (it == "GPGGA") gga(fix) else rmc(fix) }

    /** `$GPGGA`: time, position, fix quality 1 (GPS), satellites, HDOP, altitude above mean sea level, geoid separation. */
    fun gga(fix: Fix): String {
        val hdop = fix.accuracyM?.takeIf { it > 0f }?.let { (it / UERE_M).coerceIn(0.5, 99.9) }
        // GGA wants mean sea level and the geoid's separation from the ellipsoid. Without Android 14's MSL height,
        // the ellipsoid height stands in with a separation of 0, as a receiver without a geoid model reports it.
        val msl = fix.mslAltitudeM ?: fix.ellipsoidAltitudeM
        val separation = if (fix.mslAltitudeM != null && fix.ellipsoidAltitudeM != null) fix.ellipsoidAltitudeM - fix.mslAltitudeM else msl?.let { 0.0 }
        val fields =
            listOf(
                "GPGGA",
                time(fix.utcMs),
                latitude(fix.latitude),
                if (fix.latitude < 0) "S" else "N",
                longitude(fix.longitude),
                if (fix.longitude < 0) "W" else "E",
                "1",
                field("%02d", fix.satellites?.takeIf { it > 0 }?.coerceAtMost(99)),
                field("%.1f", hdop),
                field("%.1f", msl),
                "M",
                field("%.1f", separation),
                "M",
                "",
                "",
            )
        return sentence(fields.joinToString(","))
    }

    /** `$GPRMC`: time, status A, position, speed in knots, course over ground, date, no magnetic variation, mode A. */
    fun rmc(fix: Fix): String {
        val date = Instant.ofEpochMilli(fix.utcMs).atOffset(ZoneOffset.UTC)
        val fields =
            listOf(
                "GPRMC",
                time(fix.utcMs),
                "A",
                latitude(fix.latitude),
                if (fix.latitude < 0) "S" else "N",
                longitude(fix.longitude),
                if (fix.longitude < 0) "W" else "E",
                field("%.2f", fix.speedMps?.takeIf { it >= 0f }?.let { it * KNOTS_PER_MPS }),
                field("%.1f", fix.bearingDeg?.let { ((it % 360f) + 360f) % 360f }),
                String.format(Locale.US, "%02d%02d%02d", date.dayOfMonth, date.monthValue, date.year % 100),
                "",
                "",
                "A",
            )
        return sentence(fields.joinToString(","))
    }

    /** [value] in [pattern], [Locale.US]; an empty field when there is none. */
    private fun field(
        pattern: String,
        value: Any?,
    ): String = if (value == null) "" else String.format(Locale.US, pattern, value)

    /** `$<body>*<checksum>`: the checksum is the XOR of every character between them, two upper-case hex digits. */
    fun sentence(body: String): String = "$" + body + "*" + checksum(body)

    fun checksum(body: String): String = String.format(Locale.US, "%02X", body.fold(0) { acc, c -> acc xor c.code } and 0xFF)

    /** `hhmmss.ss`, UTC. */
    private fun time(utcMs: Long): String {
        val t = Instant.ofEpochMilli(utcMs).atOffset(ZoneOffset.UTC)
        return String.format(Locale.US, "%02d%02d%02d.%02d", t.hour, t.minute, t.second, Math.floorMod(utcMs, 1000L) / 10)
    }

    /** `ddmm.mmmmm`. */
    private fun latitude(deg: Double): String = degreesMinutes(deg, 2)

    /** `dddmm.mmmmm`. */
    private fun longitude(deg: Double): String = degreesMinutes(deg, 3)

    /**
     * Whole degrees, then minutes to five decimals (about 2 cm). Rounded once, in units of 1e-5 minute, so 59.999996
     * minutes carries into the next degree instead of printing as 60.00000.
     */
    private fun degreesMinutes(
        deg: Double,
        degreeDigits: Int,
    ): String {
        val units = (abs(deg) * 60.0 * 100_000.0).roundToLong()
        val perDegree = 60L * 100_000L
        val minutes = units % perDegree
        return String.format(Locale.US, "%0${degreeDigits}d%02d.%05d", units / perDegree, minutes / 100_000L, minutes % 100_000L)
    }
}
