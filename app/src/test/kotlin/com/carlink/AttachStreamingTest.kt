package com.carlink

import com.carlink.CarlinkManager.State
import com.carlink.link.WifiAttach
import com.carlink.ocbm.HelloReplay
import com.carlink.ocbm.Ocbm
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 24 Nov bench (12:22) replayed through the pieces CarlinkManager runs a start's first seconds with, in its order: the
 * box's state at SUBSCRIBE held ([HelloReplay], from holdHelloReplay), the hold decided (onProjMode / the 3 s timeout ->
 * onReplayDecided: [WifiAttach.onDecided], attachToRunningSession, then the held key), the phone's key (sessionKeyed) and
 * its now-playing frames (markStreamingIfFirstMedia), with [StreamingStamp] deciding STREAMING.
 *
 * On that bench Carlink attached over Wi-Fi to the session the adapter had started by itself, the phone's first frame was
 * drawn 0.9 s later, and the session never left DEVICE_CONNECTED: a normal start reaches STREAMING at the phone's first
 * now-playing frame, which this phone had sent before Carlink was there. No now-playing frame comes in the attaches below.
 */
class AttachStreamingTest {
    /** One start, as CarlinkManager drives its state machine. */
    private class Start(
        private val overWifi: Boolean,
    ) {
        private val replay = HelloReplay()
        private val stamp = StreamingStamp()
        var state = State.CONNECTING
            private set

        init {
            // holdHelloReplay, just before SUBSCRIBE.
            stamp.reset()
            replay.start()
        }

        /** CT_PROJ_MODE from the box (CarlinkManager.onProjMode). */
        fun projMode(
            m: Byte,
            replayed: Boolean,
        ) {
            when (val v = replay.onProjMode(m, replayed)) {
                is HelloReplay.Verdict.Decided -> decided(v.release)
                else -> Unit
            }
        }

        /** The hold ran out (CarlinkManager's HELLO_REPLAY_HOLD_MS). */
        fun holdRunsOut() {
            replay.onTimeout()?.let(::decided)
        }

        /** The session's first video key (CarlinkManager.wireSessionKeyed). */
        fun key() {
            if (replay.onKeyed()) keyed()
        }

        /** A now-playing frame (CarlinkManager.processNowPlaying). */
        fun nowPlaying() {
            stamp.onMedia(state)?.let { state = it }
        }

        /** CarlinkManager.onReplayDecided: the attach first, then (live) the held key. */
        private fun decided(r: HelloReplay.Release) {
            if (WifiAttach.onDecided(r.live, r.mode, overWifi) == WifiAttach.Action.ATTACH) stamp.onAttached(state)?.let { state = it }
            if (!r.live) {
                stamp.reset()
                return
            }
            if (r.keyed) keyed()
        }

        /** CarlinkManager.sessionKeyed. */
        private fun keyed() {
            stamp.onKeyed(state)?.let { state = it }
        }
    }

    @Test
    fun `the bench's attach, decided by the box's live mode, is streaming at the phone's key`() {
        val s = Start(overWifi = true)
        s.projMode(Ocbm.PM_WIRELESS_CP, replayed = true) // the replay: held
        s.projMode(Ocbm.PM_WIRELESS_CP, replayed = false) // the attach signal right after it: decided, attach
        assertEquals("before the key", State.CONNECTING, s.state)
        s.key() // the re-keyed seam's first key, 0.3 s later
        assertEquals(State.STREAMING, s.state)
    }

    @Test
    fun `the bench's reconnect, decided by the hold running out, is streaming at the phone's key`() {
        val s = Start(overWifi = true)
        s.projMode(Ocbm.PM_WIRELESS_CP, replayed = true) // no live mode follows on a re-attach
        s.holdRunsOut()
        s.key()
        assertEquals(State.STREAMING, s.state)
    }

    @Test
    fun `an attach whose key came during the hold is streaming when the hold is decided`() {
        val s = Start(overWifi = true)
        s.projMode(Ocbm.PM_WIRELESS_CP, replayed = true)
        s.key() // held with the rest
        assertEquals(State.CONNECTING, s.state)
        s.holdRunsOut()
        assertEquals(State.STREAMING, s.state)
    }

    @Test
    fun `a start with no session running waits for the phone's own media, as before`() {
        val s = Start(overWifi = true)
        s.projMode(Ocbm.PM_NONE, replayed = true) // nothing running: decided at once, wait for the phone
        s.projMode(Ocbm.PM_WIRELESS_CP, replayed = false) // the phone comes
        s.key()
        assertEquals(State.DEVICE_CONNECTED, s.state)
        s.nowPlaying() // its session starts with Carlink there, so its first now-playing frame comes
        assertEquals(State.STREAMING, s.state)
    }

    @Test
    fun `the last host's session that ends at once leaves the next phone to its own media`() {
        val s = Start(overWifi = true)
        s.projMode(Ocbm.PM_WIRELESS_CP, replayed = true)
        s.projMode(Ocbm.PM_NONE, replayed = false) // it was the last host's, and it ended: no attach
        s.projMode(Ocbm.PM_WIRELESS_CP, replayed = false)
        s.key()
        assertEquals(State.DEVICE_CONNECTED, s.state)
        s.nowPlaying()
        assertEquals(State.STREAMING, s.state)
    }

    @Test
    fun `over USB nothing changes`() {
        val s = Start(overWifi = false)
        s.projMode(Ocbm.PM_WIRELESS_CP, replayed = true)
        s.holdRunsOut()
        s.key()
        assertEquals("USB keeps waiting for the phone's media, as it always did", State.DEVICE_CONNECTED, s.state)
        s.nowPlaying()
        assertEquals(State.STREAMING, s.state)
    }
}
