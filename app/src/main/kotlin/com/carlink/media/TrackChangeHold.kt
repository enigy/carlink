package com.carlink.media

import kotlin.math.abs

/**
 * When a song's length must wait for its title. On a song change the iPhone sends the new song's
 * length, with an elapsed time near zero, about half a second before its title (10-24 drive log: every
 * skip). Published as it arrived, the car got the OLD title with the NEW length first, and then the new
 * title with no length at all, because the title change cleared it. GM's HUD likely shows the first
 * song update after a steering-wheel skip, so it showed the song being left. The length is held until
 * the title comes, for at most [TITLE_WAIT_MS], and the two are published together.
 */
object TrackChangeHold {
    /** An elapsed time under this marks the start of a song. */
    const val NEAR_START_MS = 3_000L

    /** Longest wait for the title before a held length is published under the current title. */
    const val TITLE_WAIT_MS = 1_500L

    /** Lengths within this of each other are the same song (some sources refine it by a millisecond). */
    private const val SAME_LENGTH_MS = 1_000L

    /**
     * A length that landed this recently before a title change belongs to the NEW title: Spotify sends
     * the next song's length in a record of its own, with no elapsed time (so [startsHold] cannot see
     * it), about half a second before the title (10-25 22:07, every skip). Clearing it with the title
     * left the pane's progress bar without a length for the whole song.
     */
    fun lengthLedTitle(
        lengthArrivedAtMs: Long,
        nowMs: Long,
    ): Boolean = lengthArrivedAtMs > 0L && nowMs - lengthArrivedAtMs <= TITLE_WAIT_MS

    /**
     * A record without a new title that reports a different length near the start of a song: the next
     * song's attributes have begun arriving ahead of its title.
     */
    fun startsHold(
        reportedLength: Long?,
        currentLength: Long,
        elapsedMs: Long?,
    ): Boolean =
        reportedLength != null &&
            reportedLength > 0 &&
            abs(reportedLength - currentLength) > SAME_LENGTH_MS &&
            elapsedMs != null &&
            elapsedMs < NEAR_START_MS
}
