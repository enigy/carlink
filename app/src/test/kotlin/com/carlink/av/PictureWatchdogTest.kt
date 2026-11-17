package com.carlink.av

import com.carlink.av.PictureWatchdog.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The loading screen's last resort, sampled as CarlinkManager samples it, with made-up times: a still CarPlay screen whose
 * decoder takes the video and draws nothing is rebuilt 4 s after its first video, and falls back to 3.20's rule 3 s after
 * that rebuild; keyed with no video at all, it falls back at 7 s. Once per wait; a picture or a new wait ends it.
 */
class PictureWatchdogTest {
    private val dog = PictureWatchdog()
    private val wait = 7L

    /** Samples every 250 ms from [from] to [to], the first video at [videoIn]: the actions it took, by time. */
    private fun run(
        from: Long,
        to: Long,
        videoIn: Long,
        keyed: Boolean = true,
        forWait: Long = wait,
    ): List<Pair<Long, Action>> = (from..to step 250).mapNotNull { t -> dog.sample(t, forWait, keyed, videoIn).takeIf { it != Action.NONE }?.let { t to it } }

    @Test
    fun `a still screen whose decoder draws nothing is rebuilt at 4 s, then falls back 3 s later, once`() {
        // The first config and keyframe at 10 s, then nothing but the 2.5 s keyframe: no frame drawn at all.
        val acts = run(from = 10_000, to = 30_000, videoIn = 10_000)
        assertEquals(listOf(14_000L to Action.REBUILD, 17_000L to Action.FALL_BACK), acts)
    }

    @Test
    fun `the 2_5 s keyframe's frame before 4 s ends it with no rebuild`() {
        assertEquals(emptyList<Pair<Long, Action>>(), run(from = 10_000, to = 12_750, videoIn = 10_000))
        assertEquals("the picture is up", Action.NONE, dog.sample(13_000, PictureGate.NONE, keyed = true, videoInAtMs = 10_000))
    }

    @Test
    fun `a rebuilt decoder that draws within its 3 s means no fallback`() {
        val acts = run(from = 10_000, to = 15_500, videoIn = 10_000)
        assertEquals(listOf(14_000L to Action.REBUILD), acts)
        // The rebuild's keyframe drew at 15.6 s: the gate's wait closed, and the dog sees none.
        assertEquals(Action.NONE, dog.sample(16_000, PictureGate.NONE, keyed = true, videoInAtMs = 0))
        assertEquals(Action.NONE, dog.sample(20_000, PictureGate.NONE, keyed = true, videoInAtMs = 0))
    }

    @Test
    fun `keyed with no video at all falls back at 7 s, with nothing to rebuild`() {
        val acts = run(from = 50_000, to = 60_000, videoIn = 0)
        assertEquals(listOf(57_000L to Action.FALL_BACK), acts)
    }

    @Test
    fun `nothing while no phone's session is keyed, waiting for a phone`() {
        assertEquals(emptyList<Pair<Long, Action>>(), run(from = 0, to = 600_000, videoIn = 0, keyed = false))
    }

    @Test
    fun `a new wait starts it over, and the next phone's decoder gets its own rebuild and fallback`() {
        run(from = 10_000, to = 30_000, videoIn = 10_000)
        val acts = run(from = 40_000, to = 50_000, videoIn = 41_000, forWait = wait + 1)
        assertEquals(listOf(45_000L to Action.REBUILD, 48_000L to Action.FALL_BACK), acts)
    }

    @Test
    fun `the epoch's first video starts the count, so a later epoch's counts from its own`() {
        // A surface swap at 12 s opened a new epoch, whose first video came at 12.5 s: 4 s from that, not from 10 s.
        assertEquals(emptyList<Pair<Long, Action>>(), run(from = 10_000, to = 12_250, videoIn = 10_000))
        val acts = run(from = 12_500, to = 17_000, videoIn = 12_500)
        assertEquals(listOf(16_500L to Action.REBUILD), acts)
    }
}
