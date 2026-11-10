package com.carlink.av

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rebuild a video epoch that takes access units and draws nothing (9 Nov bench), and only then. */
class VideoStallWatchTest {
    private val watch = VideoStallWatch(VideoStallWatch.Timing(sampleMs = 2_000, stallMs = 6_000, cooldownMs = 15_000))

    @Test
    fun `input with no frame drawn for the stall time rebuilds once`() {
        assertFalse(watch.sample(1, 0, bytesIn = 0, framesRendered = 0))
        assertFalse(watch.sample(1, 2_000, bytesIn = 100, framesRendered = 0))
        assertFalse(watch.sample(1, 4_000, bytesIn = 200, framesRendered = 0))
        assertFalse(watch.sample(1, 6_000, bytesIn = 300, framesRendered = 0))
        assertTrue(watch.sample(1, 8_000, bytesIn = 400, framesRendered = 0))
    }

    @Test
    fun `frames drawn is no stall`() {
        var bytes = 0L
        var frames = 0L
        for (t in 0..20) {
            bytes += 100
            frames += 50
            assertFalse(watch.sample(1, t * 2_000L, bytes, frames))
        }
    }

    @Test
    fun `nothing arriving is no stall`() {
        for (t in 0..20) assertFalse(watch.sample(1, t * 2_000L, bytesIn = 500, framesRendered = 10))
    }

    @Test
    fun `input that stops is no stall`() {
        // 9 Nov bench: one last access unit, then the phone's session ended; rebuilt 6 s later with nothing to decode.
        assertFalse(watch.sample(1, 0, bytesIn = 1_000, framesRendered = 50))
        assertFalse(watch.sample(1, 2_000, bytesIn = 1_100, framesRendered = 50))
        for (t in 2..20) assertFalse("at ${t * 2_000} ms", watch.sample(1, t * 2_000L, bytesIn = 1_100, framesRendered = 50))
    }

    @Test
    fun `a gap in the input starts the count again`() {
        watch.sample(1, 0, 0, 0)
        watch.sample(1, 2_000, 100, 0)
        watch.sample(1, 4_000, 200, 0)
        assertFalse("nothing arrived", watch.sample(1, 6_000, 200, 0))
        assertFalse(watch.sample(1, 8_000, 300, 0))
        assertFalse(watch.sample(1, 12_000, 500, 0))
        assertTrue(watch.sample(1, 14_000, 600, 0))
    }

    @Test
    fun `a frame drawn mid-stall starts the count again`() {
        watch.sample(1, 0, 0, 0)
        watch.sample(1, 2_000, 100, 0)
        watch.sample(1, 4_000, 200, 0)
        assertFalse(watch.sample(1, 6_000, 300, 1))
        assertFalse(watch.sample(1, 8_000, 400, 1))
        assertFalse(watch.sample(1, 12_000, 500, 1))
        assertTrue(watch.sample(1, 14_000, 600, 1))
    }

    @Test
    fun `a new epoch starts over`() {
        watch.sample(1, 0, 0, 0)
        watch.sample(1, 2_000, 100, 0)
        watch.sample(1, 6_000, 200, 0)
        assertFalse("a new epoch is not judged on the old one's counters", watch.sample(2, 8_000, 0, 0))
        assertFalse(watch.sample(2, 10_000, 100, 0))
        assertFalse(watch.sample(2, 14_000, 200, 0))
        assertTrue(watch.sample(2, 16_000, 300, 0))
    }

    @Test
    fun `a decoder that never draws is rebuilt at most once per cooldown`() {
        var bytes = 0L
        var rebuilds = 0
        var seq = 1
        for (t in 0..30) {
            bytes += 100
            if (watch.sample(seq, t * 2_000L, bytes, 0)) {
                rebuilds++
                seq++
                bytes = 0
            }
        }
        // 60 s of a decoder that never draws: the first rebuild at 8 s, then no more than one per 15 s.
        assertTrue("rebuilds=$rebuilds", rebuilds in 2..4)
    }
}
