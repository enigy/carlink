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
}
