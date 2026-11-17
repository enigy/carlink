package com.carlink.av

import com.carlink.av.PictureWatch.FirstFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture that lifts the loading screen: a renderer's [PictureWatch] offering its frames to the manager's
 * [PictureGate], with made-up times. The first frame drawn for the current wait is the picture, with its video in and
 * decoder ready; a new wait takes it down; a frame from before a wait, or offered for an older one, never is.
 */
class PictureGateTest {
    private val gate = PictureGate()
    private var now = 1_000L
    private val offered = mutableListOf<Pair<Long, FirstFrame>>()

    /** One epoch's watch, as CarlinkManager.openVideoEpoch builds it: it offers to the gate, which says yes once. */
    private fun watch(accept: Boolean = true) =
        PictureWatch({ gate.waiting }, { now }, gate::videoIn) { wait, frame ->
            offered += wait to frame
            if (accept) gate.drawn(wait, frame)
        }

    /** A frame drawn now, judged under the gate's wait as the renderer reads it. */
    private fun PictureWatch.draw() = drawn(now())

    @Test
    fun `the first frame drawn is the picture, with when its video came and when its decoder was ready`() {
        val w = watch()
        w.decoderReady() // built at 1.0 s, ahead of the stream
        now = 9_000L
        w.videoIn()
        now = 9_050L
        w.videoIn() // the next message changes nothing
        now = 9_180L
        w.draw()
        assertEquals(FirstFrame(drawnAtMs = 9_180L, videoInAtMs = 9_000L, decoderReadyAtMs = 1_000L), gate.picture.value)
        now = 9_200L
        w.draw()
        assertEquals("the later frames change nothing", 9_180L, gate.picture.value?.drawnAtMs)
        assertEquals("and aren't even offered once it is up", 1, offered.size)
        assertEquals(PictureGate.NONE, gate.waiting)
    }

    @Test
    fun `a decoder built at the first frame is ready after the video came`() {
        val w = watch()
        now = 9_000L
        w.videoIn()
        now = 9_052L
        w.decoderReady()
        now = 9_230L
        w.draw()
        assertEquals(FirstFrame(9_230L, 9_000L, 9_052L), gate.picture.value)
    }

    @Test
    fun `a new wait takes the picture down until a frame drawn after it, whose video in is its own`() {
        val w = watch()
        w.decoderReady()
        now = 9_000L
        w.videoIn()
        now = 9_100L
        w.draw()
        gate.expect() // the phone left
        assertNull(gate.picture.value)
        now = 40_000L
        w.draw() // a frame with no video since the wait began: the decoder's last, drained late
        assertEquals("its video in is not known", 0L, gate.picture.value?.videoInAtMs)

        gate.expect() // the next phone's first video key
        now = 52_000L
        w.videoIn()
        now = 52_090L
        w.draw()
        assertEquals(FirstFrame(52_090L, 52_000L, 1_000L), gate.picture.value)
    }

    @Test
    fun `a frame offered for an older wait is turned away, and the next one for the current wait is taken`() {
        val before = gate.waiting
        gate.expect() // the key landed while a renderer was offering a frame read under the old wait
        assertFalse(gate.drawn(before, FirstFrame(1_000L, 0L, 0L)))
        assertNull(gate.picture.value)
        assertTrue(gate.drawn(gate.waiting, FirstFrame(1_016L, 0L, 0L)))
        assertEquals(1_016L, gate.picture.value?.drawnAtMs)
        assertFalse("taken once", gate.drawn(gate.waiting, FirstFrame(1_033L, 0L, 0L)))
    }

    @Test
    fun `a frame the manager turns away, from a retired epoch, leaves the gate waiting for the current one's`() {
        val retired = watch(accept = false)
        val current = watch()
        now = 2_000L
        retired.draw()
        assertNull(gate.picture.value)
        now = 2_016L
        current.draw()
        assertEquals(2_016L, gate.picture.value?.drawnAtMs)
    }

    @Test
    fun `nothing is offered while the gate does not wait`() {
        val w = watch()
        w.draw() // the picture
        repeat(3) { w.draw() }
        w.videoIn()
        assertEquals(1, offered.size)
    }

    @Test
    fun `a frame of an access unit fed before the wait began is stale, and so is any before this wait's first`() {
        val w = watch()
        assertTrue("nothing fed under this wait yet: any frame is an earlier one's", w.stale(500, w.now()))
        w.fed(1_000, w.now()) // this wait's first access unit
        assertFalse(w.stale(1_000, w.now()))
        w.draw() // its frame: the picture
        assertFalse("never while the picture is up", w.stale(500, w.now()))
        gate.expect() // the phone left and came back on the same decoder
        assertTrue(w.startsWait(w.now()))
        assertTrue("the last phone's frame, drained after the wait began", w.stale(1_000, w.now()))
        w.fed(2_000, w.now()) // this wait's first access unit
        assertFalse(w.startsWait(w.now()))
        assertTrue("fed before it", w.stale(1_999, w.now()))
        assertFalse(w.stale(2_000, w.now()))
        w.fed(2_100, w.now())
        assertFalse("the wait's first stays where its frames begin", w.stale(2_050, w.now()))
    }

    @Test
    fun `each step's one read of the wait holds for the whole step, whatever begins meanwhile`() {
        val w = watch()
        val before = w.now() // read for an access unit, before it is queued
        gate.expect() // the phone leaves right as it is queued
        w.fed(1_000, before)
        assertTrue("not the new wait's first", w.startsWait(w.now()))
        assertTrue("its frame is stale for the new wait", w.stale(1_000, w.now()))
        val judged = w.now() // a frame judged under the new wait
        gate.expect() // and another wait begins before its offer
        w.drawn(judged)
        assertNull("offered to the wait it was judged under, and refused", gate.picture.value)
    }

    @Test
    fun `the picture's video in is the wait's first, whichever epoch drew it`() {
        val wait = gate.waiting
        val first = watch()
        now = 9_000L
        first.videoIn() // the wait's first video: this epoch's decoder draws nothing
        val rebuilt = watch()
        now = 15_000L
        rebuilt.videoIn() // the watchdog's rebuild, its keyframe
        now = 15_100L
        rebuilt.draw()
        assertEquals("the connect's video in, not the rebuilt epoch's", 9_000L, gate.picture.value?.videoInAtMs)
        assertEquals("the epoch still reads its own, for the watchdog", 15_000L, rebuilt.firstVideoAt(wait))
        gate.expect() // the next phone
        now = 20_000L
        rebuilt.videoIn()
        now = 20_050L
        rebuilt.draw()
        assertEquals("a new wait's own", 20_000L, gate.picture.value?.videoInAtMs)
    }

    @Test
    fun `the watchdog reads the epoch's first video for a wait from any thread, and none for another wait`() {
        val w = watch()
        val first = gate.waiting
        assertEquals(0L, w.firstVideoAt(first))
        now = 7_000L
        w.videoIn()
        assertEquals(7_000L, w.firstVideoAt(first))
        gate.expect()
        assertEquals("another wait's", 0L, w.firstVideoAt(gate.waiting))
    }

    @Test
    fun `the fallback holds for its wait only, refused for an older one or with the picture up, and a later frame is still the picture`() {
        val first = gate.waiting
        assertTrue(gate.fallBack(first))
        assertTrue(gate.fallenBack.value)
        assertFalse("once", gate.fallBack(first))
        assertTrue("still waiting: a frame now is the picture", gate.drawn(first, FirstFrame(9_000L, 8_950L, 8_900L)))
        assertEquals(9_000L, gate.picture.value?.drawnAtMs)
        gate.expect()
        assertFalse("a new wait starts without it", gate.fallenBack.value)
        assertFalse("an older wait's", gate.fallBack(first))
        assertTrue(gate.drawn(gate.waiting, FirstFrame(10_000L, 0L, 0L)))
        assertFalse("the picture is up", gate.fallBack(gate.waiting))
    }

    @Test
    fun `the gate's waits go to none with the picture and to the next one at each new wait`() {
        val first = gate.waits.value
        assertEquals(gate.waiting, first)
        gate.drawn(first, FirstFrame(1L, 0L, 0L))
        assertEquals(PictureGate.NONE, gate.waits.value)
        gate.expect()
        assertTrue(gate.waits.value > first)
    }
}
