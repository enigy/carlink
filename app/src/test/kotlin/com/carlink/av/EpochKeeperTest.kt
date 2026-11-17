package com.carlink.av

import com.carlink.av.EpochKeeper.Reopen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manager's paths from a look to an act, over made-up epochs on a made-up clock: the picture watchdog's looks as the
 * manager's loop makes them ([EpochKeeper.look] after each delay), the stall watch's idle look, and the phone's signs.
 * [Epochs] stands for CarlinkManager's side: it retires an epoch only while it is still the current one, as
 * reopenVideoEpoch does under the manager's lock, and opens the next.
 */
class EpochKeeperTest {
    private val gate = PictureGate()
    private var now = 0L
    private val epochs = Epochs()
    private val keeper = EpochKeeper(gate, epochs)

    /** A made-up epoch: video comes when the test says ([video]), its decoder built ahead from [builtAheadAt] or not. */
    private inner class Epoch(
        override val seq: Int,
        override val openedAtMs: Long,
        override val rebuiltFor: Long,
        override val idleDropped: Boolean,
        private val builtAheadAt: Long,
    ) : EpochKeeper.Epoch {
        private var videoWait = PictureGate.NONE
        private var videoAt = 0L
        private var read = false

        /** Video still in its pipe, not read by its renderer yet. */
        var unread = false

        override val idleAheadSince: Long get() = if (read) 0L else builtAheadAt
        override val hasVideo: Boolean get() = read || unread

        override fun firstVideoAt(wait: Long): Long = if (wait == videoWait) videoAt else 0L

        /** Its first video for the gate's wait came now. */
        fun video() {
            read = true
            if (videoWait != gate.waiting) {
                videoWait = gate.waiting
                videoAt = now
            }
        }
    }

    private inner class Epochs : EpochKeeper.Epochs {
        var cur: Epoch? = null
        var keyed = false
        var ahead = true
        val reopened = mutableListOf<Pair<Int, Reopen>>()
        val blamed = mutableListOf<Int>()
        val fell = mutableListOf<Long>()
        var keyframesSoon = 0

        /** Run between the keeper's look and its act (at the blame, before the reopen): the read thread swapping the epoch. */
        var meanwhile: (() -> Unit)? = null
        private var seq = 0

        fun open(
            rebuiltFor: Long = PictureGate.NONE,
            idleDropped: Boolean = false,
            builtAheadAt: Long = 0L,
        ): Epoch = Epoch(++seq, now, rebuiltFor, idleDropped, builtAheadAt).also { cur = it }

        override val current: EpochKeeper.Epoch? get() = cur
        override val phoneKeyed: Boolean get() = keyed
        override val canBuildAhead: Boolean get() = ahead

        override fun reopen(
            epoch: EpochKeeper.Epoch,
            why: Reopen,
            forWait: Long,
        ): Boolean {
            if (cur !== epoch) return false
            reopened += epoch.seq to why
            when (why) {
                Reopen.PICTURE -> open(rebuiltFor = forWait)
                Reopen.IDLE -> open(idleDropped = true)
                Reopen.AHEAD -> open(builtAheadAt = now)
            }
            return true
        }

        override fun blame(epoch: EpochKeeper.Epoch) {
            blamed += epoch.seq
            meanwhile?.invoke()
        }

        override fun keyframeSoon() {
            keyframesSoon++
        }

        override fun fellBack(wait: Long) {
            fell += wait
        }
    }

    /** The manager's loop from now to [to]: a delay of lookEveryMs, then a look; [at] runs what happens meanwhile, by time. */
    private fun looks(
        to: Long,
        at: (Long) -> Unit = {},
    ) {
        while (now < to) {
            val next = now + keeper.lookEveryMs()
            while (now < next) {
                now++
                at(now)
            }
            keeper.look(now)
        }
    }

    /** The phone keys: a new wait, a keyed session, its epoch as [e] (the one open, or a new one). */
    private fun key(e: Epoch? = null): Epoch {
        gate.expect()
        epochs.keyed = true
        return e ?: epochs.open()
    }

    private fun picture() = gate.drawn(gate.waiting, PictureWatch.FirstFrame(now, 0L, 0L))

    @Test
    fun `a still screen whose decoder draws nothing is rebuilt 6 s after its video, given the 2_5 s keyframe again, and falls back 5 s later`() {
        val e1 = key()
        looks(to = 20_000) { t -> if (t == 200L) e1.video() }
        assertEquals(listOf(1 to Reopen.PICTURE), epochs.reopened)
        assertEquals("blamed, if it was built ahead", listOf(1), epochs.blamed)
        assertEquals("the rebuilt decoder gets the delayed keyframe as well as its own", 1, epochs.keyframesSoon)
        assertEquals(gate.waiting, epochs.cur!!.rebuiltFor)
        assertEquals("once", listOf(gate.waiting), epochs.fell)
        assertTrue(gate.fallenBack.value)
        assertEquals(gate.waiting, keeper.settled)
    }

    @Test
    fun `a decoder that draws at its second keyframe, even a slow one, is left alone`() {
        val e1 = key()
        looks(to = 20_000) { t ->
            if (t == 200L) e1.video()
            if (t == 4_800L) picture() // the 2.5 s keyframe back 2 s late
        }
        assertEquals(emptyList<Pair<Int, Reopen>>(), epochs.reopened)
        assertEquals(emptyList<Long>(), epochs.fell)
    }

    @Test
    fun `a rebuilt decoder that draws at its re-armed keyframe is not fallen back`() {
        val e1 = key()
        looks(to = 6_250) { t -> if (t == 200L) e1.video() }
        assertEquals(listOf(1 to Reopen.PICTURE), epochs.reopened)
        val rebuilt = epochs.cur!!
        looks(to = 20_000) { t ->
            if (t == 6_450L) rebuilt.video() // its own keyframe; it needs the second
            if (t == 9_700L) picture() // the re-armed 2.5 s keyframe, back slow: 3.45 s after the rebuild
        }
        assertEquals(emptyList<Long>(), epochs.fell)
    }

    @Test
    fun `a picture drawn just before a look is answered with nothing`() {
        val e1 = key()
        // The look at 6.25 s would rebuild (video at 0.2 s); the frame comes 1 ms before it, during the delay.
        looks(to = 6_500) { t ->
            if (t == 200L) e1.video()
            if (t == 6_249L) picture()
        }
        assertEquals(emptyList<Pair<Int, Reopen>>(), epochs.reopened)
    }

    @Test
    fun `an epoch reopened mid-wait by something else counts from its own open, never falls back at once`() {
        key()
        looks(to = 1_000)
        epochs.cur = null // the display went off inside the grace: the surface and its epoch are gone
        looks(to = 9_000)
        val back = epochs.open() // the app in front again: a new epoch, its keyframe asked
        looks(to = 9_500) { t -> if (t == 9_300L) back.video() }
        looks(to = 10_000) { t -> if (t == 9_600L) picture() }
        assertEquals(emptyList<Long>(), epochs.fell)
        assertEquals(emptyList<Pair<Int, Reopen>>(), epochs.reopened)
    }

    @Test
    fun `an epoch that never gets video after a mid-wait reopen is rebuilt 4 s from its open, then falls back`() {
        key()
        looks(to = 1_000)
        epochs.cur = null
        looks(to = 9_000)
        epochs.open()
        looks(to = 12_750)
        assertEquals("none yet: 4 s from its open at 9 s, not from the key", emptyList<Pair<Int, Reopen>>(), epochs.reopened)
        looks(to = 13_000)
        assertEquals(listOf(2 to Reopen.PICTURE), epochs.reopened)
        looks(to = 17_750)
        assertEquals(emptyList<Long>(), epochs.fell)
        looks(to = 18_000)
        assertEquals(1, epochs.fell.size)
    }

    @Test
    fun `an epoch that replaced the rebuild's own gets its own chance, not the rebuild's fallback clock`() {
        val e1 = key()
        looks(to = 6_250) { t -> if (t == 200L) e1.video() } // rebuilt at 6.25 s
        looks(to = 7_000)
        epochs.cur = null // the rebuild's epoch went with the surface
        looks(to = 12_000) // past the rebuild's 5 s
        val back = epochs.open()
        looks(to = 13_000) { t -> if (t == 12_200L) back.video() }
        assertEquals(emptyList<Long>(), epochs.fell)
        looks(to = 13_500) { t -> if (t == 13_100L) picture() }
        assertEquals(emptyList<Long>(), epochs.fell)
    }

    @Test
    fun `with a consumer gone after the last phone left, the next phone's wait with no video is rebuilt and its picture comes`() {
        val e1 = key()
        looks(to = 500) { t -> if (t == 200L) e1.video() }
        picture()
        gate.expect() // the phone left: its last access units drain, and the decoder throws; the consumer is gone
        epochs.keyed = false
        looks(to = 30_000)
        key(e1) // the next phone keys; its video goes into e1's closed pipe, and never reaches it
        val keyedAt = now
        looks(to = keyedAt + 3_750)
        assertEquals(emptyList<Pair<Int, Reopen>>(), epochs.reopened)
        looks(to = keyedAt + 4_500)
        assertEquals("rebuilt, not fallen back", listOf(1 to Reopen.PICTURE), epochs.reopened)
        val rebuilt = epochs.cur!!
        val rebuiltAt = now
        looks(to = rebuiltAt + 6_000) { t ->
            if (t == rebuiltAt + 150) rebuilt.video()
            if (t == rebuiltAt + 250) picture()
        }
        assertEquals(emptyList<Long>(), epochs.fell)
        assertNotNull(gate.picture.value)
    }

    @Test
    fun `nothing happens however long the wait while no keyed phone is here`() {
        gate.expect() // the phone left; the session stays up
        epochs.open()
        looks(to = 120_000)
        assertEquals(emptyList<Pair<Int, Reopen>>(), epochs.reopened)
        assertEquals(emptyList<Long>(), epochs.fell)
        assertEquals("seldom", 1_000L, keeper.lookEveryMs())
    }

    @Test
    fun `a rebuild decided on an epoch the read thread swapped meanwhile leaves the new one alone`() {
        val e1 = key()
        var swapped: EpochKeeper.Epoch? = null
        epochs.meanwhile = { if (swapped == null) swapped = epochs.open() } // a codec change, on the read thread
        looks(to = 6_250) { t -> if (t == 200L) e1.video() }
        assertEquals(emptyList<Pair<Int, Reopen>>(), epochs.reopened)
        assertSame("the read thread's epoch stays", swapped, epochs.cur)
        assertEquals("no keyframe for a rebuild that didn't happen", 0, epochs.keyframesSoon)
    }

    @Test
    fun `the fallback settles the wait, and later looks act on nothing until the next one`() {
        val e1 = key()
        looks(to = 11_250) { t -> if (t == 200L) e1.video() }
        assertEquals(1, epochs.fell.size)
        val rebuilds = epochs.reopened.size
        looks(to = 60_000)
        assertEquals(rebuilds, epochs.reopened.size)
        assertEquals(1, epochs.fell.size)
        val next = key(epochs.cur) // the next phone
        assertFalse(gate.fallenBack.value)
        looks(to = 66_250) { t -> if (t == 60_200L) next.video() }
        assertEquals("the next wait's own rebuild", rebuilds + 1, epochs.reopened.size)
    }

    @Test
    fun `an idle decoder built ahead is released after a minute with no phone, and built ahead again when one is coming`() {
        now = 5_000 // display on
        val e1 = epochs.open(builtAheadAt = now)
        now = 64_999
        assertFalse(keeper.idleLook(now, e1))
        now = 65_000
        assertTrue(keeper.idleLook(now, e1))
        assertEquals(listOf(1 to Reopen.IDLE), epochs.reopened)
        val dropped = epochs.cur!!
        assertTrue(dropped.idleDropped)
        now = 70_000
        keeper.phoneComing(now) // the adapter's Bluetooth link to a phone
        assertEquals(listOf(1 to Reopen.IDLE, 2 to Reopen.AHEAD), epochs.reopened)
        keeper.phoneComing(now + 1_000) // the next phase: the epoch builds ahead already
        assertEquals(2, epochs.reopened.size)
        now = 100_000
        assertFalse("a phone on its way holds it a minute from the latest sign", keeper.idleLook(now, epochs.cur!!))
    }

    @Test
    fun `video still unread in the pipe keeps the idle decoder, and keeps a released one from being rebuilt`() {
        now = 5_000
        val e1 = epochs.open(builtAheadAt = now)
        e1.unread = true // the phone's VideoConfig, written by the read thread, not read yet
        now = 65_000
        assertFalse(keeper.idleLook(now, e1))
        assertEquals(emptyList<Pair<Int, Reopen>>(), epochs.reopened)
        e1.unread = false
        assertTrue(keeper.idleLook(now, e1))
        val dropped = epochs.cur!!
        dropped.unread = true
        keeper.phoneComing(now + 5_000)
        assertEquals(listOf(1 to Reopen.IDLE), epochs.reopened)
    }

    @Test
    fun `with building ahead off, a phone coming rebuilds nothing`() {
        now = 5_000
        val e1 = epochs.open(builtAheadAt = now)
        now = 65_000
        assertTrue(keeper.idleLook(now, e1))
        epochs.ahead = false
        keeper.phoneComing(now + 5_000)
        assertEquals(listOf(1 to Reopen.IDLE), epochs.reopened)
    }
}
