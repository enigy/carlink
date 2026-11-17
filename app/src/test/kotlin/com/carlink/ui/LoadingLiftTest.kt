package com.carlink.ui

import com.carlink.CarlinkManager.State
import com.carlink.StreamingStamp
import com.carlink.av.PictureGate
import com.carlink.av.PictureWatch
import com.carlink.av.PictureWatchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the loading screen lifts and touch reaches CarPlay: the phone's first frame drawn, in a session that is up, and
 * nothing else. The connects as the manager reports them, with the gate between, and made-up times.
 */
class LoadingLiftTest {
    private val gate = PictureGate()
    private var now = 0L
    private val renderer = PictureWatch({ gate.waiting }, { now }, gate::videoIn) { wait, frame -> gate.drawn(wait, frame) }

    /** A frame drawn now, judged under the gate's wait as the renderer reads it. */
    private fun PictureWatch.draw() = drawn(now())

    private fun lifted(state: State) = LoadingLift.lifted(state, gate.picture.value != null, gate.fallenBack.value)

    @Test
    fun `the phone's first frame lifts it once a session is up, and STREAMING alone never does`() {
        for (state in State.entries) assertFalse("$state with no frame", LoadingLift.lifted(state, pictureDrawn = false))
        assertFalse("a frame under a session not yet up", LoadingLift.lifted(State.CONNECTING, pictureDrawn = true))
        assertFalse("nor after it ended", LoadingLift.lifted(State.DISCONNECTED, pictureDrawn = true))
        assertTrue(LoadingLift.lifted(State.DEVICE_CONNECTED, pictureDrawn = true))
        assertTrue(LoadingLift.lifted(State.STREAMING, pictureDrawn = true))
    }

    @Test
    fun `a wake whose phone's media comes before its first frame lifts at the frame, not at STREAMING`() {
        gate.expect() // the wake's start
        gate.expect() // the phone's first video key
        assertFalse(lifted(State.DEVICE_CONNECTED))
        assertFalse("STREAMING 80 ms before the frame", lifted(State.STREAMING))
        now = 8_160L
        renderer.draw()
        assertTrue(lifted(State.STREAMING))
    }

    @Test
    fun `an app start whose box replays the last host's session lifts only at the phone's own first frame`() {
        gate.expect() // the app start's start
        now = 2_000L
        renderer.draw() // a frame of the replayed session, on the surface under the loading screen
        assertFalse("the session is not up", lifted(State.CONNECTING))
        gate.expect() // the phone's own first video key, seconds later
        assertFalse("the last host's frame never lifts it", lifted(State.DEVICE_CONNECTED))
        now = 12_400L
        renderer.draw()
        assertTrue(lifted(State.DEVICE_CONNECTED))
    }

    @Test
    fun `the phone leaving brings it back until the next phone draws, whatever the state says`() {
        gate.expect()
        renderer.draw()
        assertTrue(lifted(State.STREAMING))
        gate.expect() // the phone left
        assertFalse(lifted(State.DEVICE_CONNECTED))
        assertFalse("even before the state follows", lifted(State.STREAMING))
        gate.expect() // the next phone's key
        renderer.draw()
        assertTrue(lifted(State.DEVICE_CONNECTED))
    }

    @Test
    fun `a session that ended takes it down at once, and the next one waits for its own frame`() {
        gate.expect()
        renderer.draw()
        assertTrue(lifted(State.STREAMING))
        assertFalse("disconnected, the last picture still held", lifted(State.DISCONNECTED))
        gate.expect() // the next start
        assertFalse(lifted(State.CONNECTING))
        assertFalse(lifted(State.DEVICE_CONNECTED))
    }

    @Test
    fun `the watchdog's fallback lifts it at STREAMING with no frame, as 3_20 did, and not before`() {
        assertTrue(LoadingLift.lifted(State.STREAMING, pictureDrawn = false, fellBack = true))
        assertFalse("no phone's media yet: 3.20 waited too", LoadingLift.lifted(State.DEVICE_CONNECTED, pictureDrawn = false, fellBack = true))
        assertFalse(LoadingLift.lifted(State.CONNECTING, pictureDrawn = false, fellBack = true))
        assertFalse(LoadingLift.lifted(State.DISCONNECTED, pictureDrawn = false, fellBack = true))
    }

    @Test
    fun `a still screen whose decoder draws nothing is rebuilt, then lifted by STREAMING, so the screen never stays locked`() {
        val dog = PictureWatchdog()
        gate.expect() // the wake's start
        gate.expect() // the phone's key: its config and first keyframe come at 10 s, and its decoder draws nothing
        val wait = gate.waiting
        val acts = (10_000L..20_000L step 250).map { t -> t to dog.sample(t, gate.waiting, keyed = true, videoInAtMs = 10_000) }
        val rebuild = acts.first { it.second == PictureWatchdog.Action.REBUILD }.first
        assertEquals("4 s after the first video", 14_000L, rebuild)
        assertFalse("locked until the fallback", lifted(State.STREAMING))
        val fallBack = acts.first { it.second == PictureWatchdog.Action.FALL_BACK }.first
        assertEquals("3 s after the rebuild", 17_000L, fallBack)
        assertTrue(gate.fallBack(wait))
        assertTrue("STREAMING lifts it, and touch goes through with it", lifted(State.STREAMING))
        assertFalse(lifted(State.DEVICE_CONNECTED))
        now = 21_000L
        renderer.draw() // the 30 s keyframe, or a touch that made CarPlay draw: the picture after all
        assertTrue(lifted(State.DEVICE_CONNECTED))
        gate.expect() // the phone left: the next one waits for its own frame, no fallback carried over
        assertFalse(lifted(State.STREAMING))
    }

    @Test
    fun `a phone that left arms no fallback, so the next phone's media before its key lifts nothing`() {
        val dog = PictureWatchdog()
        val stamp = StreamingStamp()
        gate.expect() // the phone's key
        var state = stamp.onKeyed(State.CONNECTING) ?: State.CONNECTING
        renderer.draw()
        assertTrue(lifted(state))
        gate.expect() // the phone left: the session stays DEVICE_CONNECTED, with no phone
        stamp.reset()
        for (t in 0L..60_000L step 250) {
            assertEquals("a minute with no phone", PictureWatchdog.Action.NONE, dog.sample(t, gate.waiting, stamp.phoneKeyedIn(state), 0L))
        }
        state = stamp.onMedia(state) ?: state // the next phone's media, 1.7 s before its key
        assertEquals(State.STREAMING, state)
        assertFalse("STREAMING over the last phone's frame, before the next one's key: still covered", lifted(state))
        gate.expect() // its key
        state = stamp.onKeyed(state) ?: state
        assertFalse(lifted(state))
        now = 70_000L
        renderer.draw()
        assertTrue(lifted(state))
    }
}
