package com.carlink.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The media card's position between the iPhone's half-second ticks, and its clock text. */
class NowPlayingStateTest {
    private val track = NowPlayingInfo(title = "Midnight City", artist = "M83", album = "Hurry Up, We're Dreaming", appName = "Music", durationMs = 243_000L)

    @Test
    fun `a playing track runs forward from the last tick`() {
        val s = NowPlayingState(track, playing = true, positionMs = 102_000L, positionAtMs = 10_000L)
        assertEquals(102_000L, s.positionAt(10_000L))
        assertEquals(102_400L, s.positionAt(10_400L))
    }

    @Test
    fun `a paused track holds its position`() {
        val s = NowPlayingState(track, playing = false, positionMs = 102_000L, positionAtMs = 10_000L)
        assertEquals(102_000L, s.positionAt(99_000L))
    }

    @Test
    fun `the position never runs past the song or before its start`() {
        val s = NowPlayingState(track, playing = true, positionMs = 242_000L, positionAtMs = 10_000L)
        assertEquals(243_000L, s.positionAt(20_000L))
        assertEquals(0L, NowPlayingState(track, playing = true, positionMs = -5L, positionAtMs = 0L).positionAt(1L))
    }

    @Test
    fun `without a tick stamp there is nothing to run forward from`() {
        assertEquals(5_000L, NowPlayingState(track, playing = true, positionMs = 5_000L, positionAtMs = 0L).positionAt(30_000L))
    }

    @Test
    fun `only a named track is a card`() {
        assertFalse(NowPlayingState.NONE.hasTrack)
        assertTrue(NowPlayingState(track, playing = false, positionMs = 0L, positionAtMs = 0L).hasTrack)
    }

    @Test
    fun `clock text`() {
        assertEquals("0:00", NowPlayingState.clock(0L))
        assertEquals("0:00", NowPlayingState.clock(-900L))
        assertEquals("1:42", NowPlayingState.clock(102_400L))
        assertEquals("4:03", NowPlayingState.clock(243_000L))
        assertEquals("1:02:05", NowPlayingState.clock(3_725_000L))
    }
}
