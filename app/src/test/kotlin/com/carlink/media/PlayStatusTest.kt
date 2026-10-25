package com.carlink.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's playbackStatus -> the session's PLAYING bit; a seek is not a pause. */
class PlayStatusTest {
    @Test
    fun playAndPauseAreDefinitive() {
        assertTrue(PlayStatus.playing(PlayStatus.PLAY, last = false))
        assertFalse(PlayStatus.playing(PlayStatus.PAUSE, last = true))
        assertFalse(PlayStatus.playing(PlayStatus.STOP, last = true))
    }

    @Test
    fun aSeekLeavesTheLastAnswerStanding() {
        // The run of seek statuses on a track change (10-23) must not read as a pause mid-music.
        assertTrue(PlayStatus.playing(3, last = true))
        assertTrue(PlayStatus.playing(4, last = true))
        assertFalse("seeking while paused is still paused", PlayStatus.playing(3, last = false))
        assertTrue("no status at all: unchanged", PlayStatus.playing(null, last = true))
    }

    @Test
    fun onlyThePhonesOwnStopOrPauseOverridesAudibleAudio() {
        assertTrue(PlayStatus.stopped(PlayStatus.PAUSE))
        assertTrue(PlayStatus.stopped(PlayStatus.STOP))
        assertFalse(PlayStatus.stopped(PlayStatus.PLAY))
        assertFalse(PlayStatus.stopped(3))
        assertFalse(PlayStatus.stopped(null))
    }
}
