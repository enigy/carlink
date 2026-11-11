package com.carlink.fuel

/**
 * When the Fuel card looks stations up (the brief of 11 Nov), whatever the source: once when the card first shows with
 * Nearby stations on; then, while the card is up, only after the car has moved about 3 miles (5 km) from the last
 * lookup or 15 minutes have passed; and once when low fuel comes on. Never before the card has shown, and never without
 * a position. Plain Kotlin: [now] is a monotonic clock, milliseconds.
 */
class LookupThrottle(
    private val moveM: Double = MOVE_M,
    private val ageMs: Long = AGE_MS,
) {
    private var lastAt: GeoPoint? = null
    private var lastMs = 0L
    private var shown = false

    /** Whether to look up now: the card is [visible] at [here], and low fuel just came on ([lowFuelRose]). */
    fun due(
        now: Long,
        here: GeoPoint?,
        visible: Boolean,
        lowFuelRose: Boolean = false,
    ): Boolean {
        if (visible) shown = true
        if (!shown || here == null) return false
        val last = lastAt ?: return visible || lowFuelRose
        return when {
            lowFuelRose -> true
            !visible -> false
            else -> Geo.metres(last, here) >= moveM || now - lastMs >= ageMs
        }
    }

    /** A lookup ran at [here], [now]. */
    fun ran(
        now: Long,
        here: GeoPoint,
    ) {
        lastAt = here
        lastMs = now
    }

    /** Nearby stations went off, or its source changed: the next showing looks up again. */
    fun reset() {
        lastAt = null
        lastMs = 0L
    }

    companion object {
        const val MOVE_M = 5_000.0
        const val AGE_MS = 15 * 60_000L
    }
}
