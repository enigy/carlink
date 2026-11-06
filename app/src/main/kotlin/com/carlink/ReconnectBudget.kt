package com.carlink

/**
 * The reconnect chain's attempts: [next] gives each attempt's backoff (2 s, 4 s, 8 s, 16 s, then capped) and counts
 * it, and gives null once [max] attempts are used, so a dead adapter can't spin forever.
 *
 * A link that comes back and then stays up earns a fresh chain: [linkUp] marks when the adapter answered CT_HELLO,
 * and the next loss starts at attempt 1 only if the link had been up at least [stableMs]. Before, every loss in a
 * drive spent from one budget (bench, 6 Nov: after a bridge restart the next loss began at 2/5 with a 4 s backoff,
 * and the fifth loss in a drive gave up). Not at CT_HELLO_ACK itself: a link that answers HELLO and drops would then
 * reconnect every 2 s for good, and the box escalates on five presence edges in twenty seconds, toward a reboot that
 * spends from a persistent budget; [stableMs] is longer than that window. The same rule as shortLivedStreamingCount:
 * a session that survived, not a connect, is the reset point. [reset] starts over at once (display on, reconnecting
 * called off).
 */
internal class ReconnectBudget(
    private val max: Int,
    private val initialDelayMs: Long,
    private val maxDelayMs: Long,
    private val stableMs: Long,
) {
    /** Attempts used since the chain last started over; the one in flight included. */
    var used: Int = 0
        private set

    // When the adapter last answered CT_HELLO (a monotonic clock); null while the link is down.
    private var upSince: Long? = null

    /** The adapter answered CT_HELLO at [now]. */
    fun linkUp(now: Long) {
        upSince = now
    }

    /**
     * The link is lost at [now]: the next attempt's backoff, counted, from a fresh chain if the link had been up
     * [stableMs] or more. Null when the budget is spent, which also starts it over.
     */
    fun next(now: Long): Long? {
        val since = upSince
        upSince = null
        if (since != null && now - since >= stableMs) used = 0
        if (used >= max) {
            used = 0
            return null
        }
        val delay = minOf(initialDelayMs shl used, maxDelayMs)
        used++
        return delay
    }

    fun reset() {
        used = 0
        upSince = null
    }
}
