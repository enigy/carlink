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

    /**
     * Whether to look up now: the card is [visible] at [here], low fuel just came on ([lowFuelRose]), or the driver [asked]
     * (the card's refresh, parked: past the 5 km and 15 minutes, owner, 12 Nov).
     */
    fun due(
        now: Long,
        here: GeoPoint?,
        visible: Boolean,
        lowFuelRose: Boolean = false,
        asked: Boolean = false,
    ): Boolean {
        if (visible) shown = true
        if (!shown || here == null) return false
        val last = lastAt ?: return visible || lowFuelRose
        return when {
            lowFuelRose -> true
            !visible -> false
            asked -> true
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

/**
 * When the Fuel card's refresh may ask (owner, 12 Nov), so taps neither stack nor hammer a service, nor spend the driver's
 * Google lookups: not while a lookup runs, nor while a busy service's own retry waits, nor before [restUntil], a minute
 * ([REST_MS]) after the last lookup ended. After "No internet" or a failed lookup nothing waits, so a tap tries again
 * once the minute is up. Plain Kotlin: [now] is a monotonic clock, milliseconds.
 */
object RefreshGate {
    const val REST_MS = 60_000L

    fun allows(
        now: Long,
        restUntil: Long,
        looking: Boolean,
        waiting: Boolean,
    ): Boolean = !looking && !waiting && now >= restUntil

    /**
     * Tell [set] the time from [clock] now, and again each time [sleep] has waited out what is left until [restUntil].
     * The clock is read first, never taken from an earlier tick: a rest that starts later than the last tick still
     * ends at its own [restUntil].
     */
    suspend fun tick(
        restUntil: Long,
        clock: () -> Long,
        sleep: suspend (Long) -> Unit,
        set: (Long) -> Unit,
    ) {
        var now = clock()
        set(now)
        while (now < restUntil) {
            sleep(restUntil - now)
            now = clock()
            set(now)
        }
    }
}
