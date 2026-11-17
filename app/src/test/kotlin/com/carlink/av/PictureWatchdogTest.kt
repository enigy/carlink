package com.carlink.av

import com.carlink.av.PictureWatchdog.Action
import com.carlink.av.PictureWatchdog.Epoch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The loading screen's last resort, sampled as the manager's keeper samples it, with made-up times: a still CarPlay
 * screen whose decoder takes the video and draws nothing is rebuilt 6 s after its first video, and falls back to 3.20's
 * rule 5 s after that rebuild; keyed with no video at all, it is rebuilt at 4 s and falls back 5 s later. Its clocks are
 * the epoch's: an epoch something else opened mid-wait counts from its own open. A picture or a new wait ends it.
 */
class PictureWatchdogTest {
    private val dog = PictureWatchdog()
    private val wait = 7L

    /** Samples every 250 ms from [from] to [to] with [epoch] current: the actions it took, by time. */
    private fun run(
        from: Long,
        to: Long,
        epoch: Epoch?,
        keyed: Boolean = true,
        forWait: Long = wait,
    ): List<Pair<Long, Action>> = (from..to step 250).mapNotNull { t -> dog.sample(t, forWait, keyed, epoch).takeIf { it != Action.NONE }?.let { t to it } }

    /** Epoch [seq], opened at [openedAt] by something other than the watchdog (or by its rebuild for [rebuiltFor]), its first video at [videoIn]. */
    private fun epoch(
        seq: Int,
        openedAt: Long,
        videoIn: Long = 0L,
        rebuiltFor: Long = PictureGate.NONE,
    ) = Epoch(seq, openedAt, rebuiltFor, videoIn)

    @Test
    fun `a still screen whose decoder draws nothing is rebuilt at 6 s, then falls back 5 s after the rebuild, once`() {
        // The first config and keyframe at 10 s, then nothing but the 2.5 s keyframe: no frame drawn at all.
        assertEquals(listOf(16_000L to Action.REBUILD), run(from = 10_000, to = 16_000, epoch = epoch(1, 9_500, videoIn = 10_000)))
        val rebuilt = epoch(2, 16_000, rebuiltFor = wait)
        assertEquals(listOf(21_000L to Action.FALL_BACK), run(from = 16_250, to = 40_000, epoch = rebuilt))
    }

    @Test
    fun `a decoder that draws at its second keyframe, even a slow one, is not torn down`() {
        // The 2.5 s keyframe came back 2 s late: its frame at 14.5 s, still inside the 6 s.
        assertEquals(emptyList<Pair<Long, Action>>(), run(from = 10_000, to = 14_500, epoch = epoch(1, 9_500, videoIn = 10_000)))
        assertEquals("the picture is up", Action.NONE, dog.sample(14_750, PictureGate.NONE, keyed = true, epoch = epoch(1, 9_500, videoIn = 10_000)))
    }

    @Test
    fun `a rebuilt decoder that draws at its re-armed 2_5 s keyframe means no fallback`() {
        run(from = 10_000, to = 16_000, epoch = epoch(1, 9_500, videoIn = 10_000))
        val rebuilt = epoch(2, 16_000, videoIn = 16_200, rebuiltFor = wait)
        // Its own keyframe at once, and the 2.5 s one, back at 18.9 s: the frame drawn then, 2.9 s after the rebuild.
        assertEquals(emptyList<Pair<Long, Action>>(), run(from = 16_250, to = 18_750, epoch = rebuilt))
        assertEquals(Action.NONE, dog.sample(19_000, PictureGate.NONE, keyed = true, epoch = rebuilt))
    }

    @Test
    fun `keyed with no video at all is rebuilt at 4 s, then falls back 5 s later`() {
        // A consumer gone with its pipe closed: the phone's video never reaches the epoch.
        assertEquals(listOf(54_000L to Action.REBUILD), run(from = 50_000, to = 54_000, epoch = epoch(1, 20_000)))
        assertEquals(listOf(59_000L to Action.FALL_BACK), run(from = 54_250, to = 70_000, epoch = epoch(2, 54_000, rebuiltFor = wait)))
    }

    @Test
    fun `nothing while no phone's session is keyed, waiting for a phone`() {
        assertEquals(emptyList<Pair<Long, Action>>(), run(from = 0, to = 600_000, epoch = epoch(1, 0), keyed = false))
    }

    @Test
    fun `an epoch something else opened mid-wait counts from its own open, not from the key`() {
        // Keyed at 10 s; the surface went at 11 s (no epoch); back at 19 s, its keyframe in flight.
        run(from = 10_000, to = 11_000, epoch = epoch(1, 9_500))
        assertEquals(emptyList<Pair<Long, Action>>(), run(from = 11_250, to = 19_000, epoch = null))
        val back = epoch(2, 19_000)
        assertEquals("not at once: its video gets its 4 s", listOf(23_000L to Action.REBUILD), run(from = 19_250, to = 23_000, epoch = back))
    }

    @Test
    fun `an epoch that replaced the rebuild's own gets a rebuild and a fallback of its own`() {
        run(from = 10_000, to = 16_000, epoch = epoch(1, 9_500, videoIn = 10_000)) // rebuilt at 16 s
        run(from = 16_250, to = 17_000, epoch = epoch(2, 16_000, rebuiltFor = wait))
        // The rebuild's epoch went with the surface at 17 s; the next came back at 22 s, past the 5 s from the rebuild.
        assertEquals(emptyList<Pair<Long, Action>>(), run(from = 17_250, to = 22_000, epoch = null))
        val back = epoch(3, 22_000, videoIn = 22_200)
        assertEquals("no fallback at once", Action.NONE, dog.sample(22_250, wait, keyed = true, epoch = back))
        assertEquals(listOf(28_200L to Action.REBUILD), run(from = 22_450, to = 28_200, epoch = back))
    }

    @Test
    fun `an epoch the rebuild opened for an older wait is not the new wait's own`() {
        val stale = epoch(5, 30_000, rebuiltFor = wait)
        assertEquals("its own 4 s, no fallback clock", listOf(34_000L to Action.REBUILD), run(from = 30_000, to = 34_000, epoch = stale, forWait = wait + 1))
    }

    @Test
    fun `a new wait starts it over, and the next phone's decoder gets its own rebuild and fallback`() {
        run(from = 10_000, to = 30_000, epoch = epoch(1, 9_500, videoIn = 10_000))
        val next = epoch(1, 9_500, videoIn = 41_000) // the same epoch, kept: its first video for the new wait
        assertEquals(listOf(47_000L to Action.REBUILD), run(from = 40_000, to = 47_000, epoch = next, forWait = wait + 1))
        assertEquals(listOf(52_000L to Action.FALL_BACK), run(from = 47_250, to = 60_000, epoch = epoch(2, 47_000, rebuiltFor = wait + 1), forWait = wait + 1))
    }
}
