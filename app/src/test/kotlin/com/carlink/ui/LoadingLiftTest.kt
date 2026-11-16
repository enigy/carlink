package com.carlink.ui

import com.carlink.CarlinkManager.State
import com.carlink.av.PictureGate
import com.carlink.av.PictureWatch
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
    private val renderer = PictureWatch({ gate.waiting }, { now }) { wait, frame -> gate.drawn(wait, frame) }

    private fun lifted(state: State) = LoadingLift.lifted(state, gate.picture.value != null)

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
        renderer.drawn()
        assertTrue(lifted(State.STREAMING))
    }

    @Test
    fun `an app start whose box replays the last host's session lifts only at the phone's own first frame`() {
        gate.expect() // the app start's start
        now = 2_000L
        renderer.drawn() // a frame of the replayed session, on the surface under the loading screen
        assertFalse("the session is not up", lifted(State.CONNECTING))
        gate.expect() // the phone's own first video key, seconds later
        assertFalse("the last host's frame never lifts it", lifted(State.DEVICE_CONNECTED))
        now = 12_400L
        renderer.drawn()
        assertTrue(lifted(State.DEVICE_CONNECTED))
    }

    @Test
    fun `the phone leaving brings it back until the next phone draws, whatever the state says`() {
        gate.expect()
        renderer.drawn()
        assertTrue(lifted(State.STREAMING))
        gate.expect() // the phone left
        assertFalse(lifted(State.DEVICE_CONNECTED))
        assertFalse("even before the state follows", lifted(State.STREAMING))
        gate.expect() // the next phone's key
        renderer.drawn()
        assertTrue(lifted(State.DEVICE_CONNECTED))
    }

    @Test
    fun `a session that ended takes it down at once, and the next one waits for its own frame`() {
        gate.expect()
        renderer.drawn()
        assertTrue(lifted(State.STREAMING))
        assertFalse("disconnected, the last picture still held", lifted(State.DISCONNECTED))
        gate.expect() // the next start
        assertFalse(lifted(State.CONNECTING))
        assertFalse(lifted(State.DEVICE_CONNECTED))
    }
}
