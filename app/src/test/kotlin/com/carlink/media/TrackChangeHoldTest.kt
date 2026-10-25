package com.carlink.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A new song's length that arrives ahead of its title waits for the title. */
class TrackChangeHoldTest {
    @Test
    fun aNewLengthAtTheStartOfASongWaitsForTheTitle() {
        // 10-24 20:00:34: Daisies' 138 s arrived at 0.5 s elapsed while Never Be Alone (180 s) was current.
        assertTrue(TrackChangeHold.startsHold(reportedLength = 138_000, currentLength = 180_000, elapsedMs = 546))
        assertTrue("first song of a session", TrackChangeHold.startsHold(reportedLength = 180_000, currentLength = 0, elapsedMs = 0))
    }

    @Test
    fun theSameSongIsNeverHeld() {
        assertFalse("a millisecond refinement", TrackChangeHold.startsHold(reportedLength = 207_655, currentLength = 207_654, elapsedMs = 548))
        assertFalse("no length in the record", TrackChangeHold.startsHold(reportedLength = null, currentLength = 180_000, elapsedMs = 0))
        assertFalse("unknown length", TrackChangeHold.startsHold(reportedLength = 0, currentLength = 180_000, elapsedMs = 0))
    }

    @Test
    fun aLengthChangeMidSongIsPublishedAtOnce() {
        assertFalse(TrackChangeHold.startsHold(reportedLength = 138_000, currentLength = 180_000, elapsedMs = 60_000))
        assertFalse("no elapsed time to place it", TrackChangeHold.startsHold(reportedLength = 138_000, currentLength = 180_000, elapsedMs = null))
    }
}
