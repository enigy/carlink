package com.carlink

/**
 * What Carlink does when the head unit's display stays off (Scott, 5 Nov). In the truck the adapter stays powered with
 * the car off, and Carlink kept the session (and a partial wake lock) up with the screen dark, so the session never
 * ended and a confirmed "Remove stock CarPlay" never finished: the box finishes only when the session ends.
 *
 * Display off starts a [GRACE_MS] wait; display-off blips mid-drive are real (Carlink2's truck logs, 9 Oct: 38 s while
 * streaming, and 4 s). At its end the session ends as Close app ends it, unless the car is moving or a call is
 * active, which wait another [GRACE_MS]. Carlink then stays dormant, with no reconnects, until the display is on again.
 */
object DisplayOff {
    /** How long the display stays off before the session ends. */
    const val GRACE_MS = 30_000L

    /**
     * After the display comes back: let a re-powered USB port settle before connecting (Carlink2's WAKE_SETTLE_MS). Only
     * when the adapter is not listed, or opening it at once failed (CarlinkManager.wakeConnect).
     */
    const val WAKE_SETTLE_MS = 2_500L

    /**
     * The wake's open at once waits this long for the adapter's first reply, not 20 s: one wake in seven got none for
     * 21 s, and the retry 2 s later connected at once (the Mac, 13 Nov). The adapter has been running all along.
     */
    const val WAKE_HELLO_MS = 3_000L

    /** A wake whose first video comes later than this had no phone for a while: its line would time nothing. */
    const val WAKE_TIMED_MAX_MS = 120_000L

    enum class Decision {
        /** The display came back: nothing to do. */
        DISPLAY_ON,

        /** Still off, but the car is moving: wait another [GRACE_MS]. */
        KEEP_DRIVING,

        /** Still off, but a phone call is active: wait another [GRACE_MS]. */
        KEEP_CALL,

        /** Still off, parked, no call: end the session and stay dormant. */
        END_SESSION,
    }

    /** At the end of a [GRACE_MS] wait. */
    fun atGraceEnd(
        displayOn: Boolean,
        driving: Boolean,
        callActive: Boolean,
    ): Decision =
        when {
            displayOn -> Decision.DISPLAY_ON
            driving -> Decision.KEEP_DRIVING
            callActive -> Decision.KEEP_CALL
            else -> Decision.END_SESSION
        }

    /** How a wake connected, for its `[startup]` line (CarlinkManager.wakeConnect). */
    enum class WakePath(
        val words: String,
    ) {
        AT_ONCE("at once, the adapter listed"),
        HELLO_MISSED("the open at once got no reply in ${WAKE_HELLO_MS / 1000} s, then the retry"),
        OPEN_FAILED("after the settle, the open at once failed"),
        NOT_LISTED("after the settle, the adapter not listed"),
    }

    /** A wake opens at once while the adapter is still listed (powered throughout); else it settles first. */
    fun firstPath(adapterListed: Boolean): WakePath = if (adapterListed) WakePath.AT_ONCE else WakePath.NOT_LISTED

    /** The wake's open at once waits [WAKE_HELLO_MS] for the adapter's first reply; every other open the usual 20 s. */
    fun helloMs(wakeOpen: Boolean): Long = if (wakeOpen) WAKE_HELLO_MS else com.carlink.ocbm.OcbmClient.HELLO_TIMEOUT_MS

    /** The wake's line at STREAMING, `elapsedMs` after display on; none past [WAKE_TIMED_MAX_MS], which timed no phone. */
    fun wakeLine(
        elapsedMs: Long,
        path: WakePath,
    ): String? = if (elapsedMs < WAKE_TIMED_MAX_MS) "[startup] wake: display on to STREAMING $elapsedMs ms (${path.words})" else null
}
