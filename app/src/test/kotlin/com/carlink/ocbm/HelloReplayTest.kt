package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The box's state at SUBSCRIBE, held until a live CT_PROJ_MODE or the hold time says whose session it is (9 Nov bench). */
class HelloReplayTest {
    private val gate = HelloReplay()

    private fun decided(v: HelloReplay.Verdict): HelloReplay.Release = (v as HelloReplay.Verdict.Decided).release

    @Test
    fun `outside a hold everything applies`() {
        assertFalse(gate.holding)
        assertEquals(HelloReplay.Verdict.Apply, gate.onProjMode(Ocbm.PM_WIRELESS_CP, replay = true))
        assertTrue(gate.onIdentity("{}"))
        assertTrue(gate.onKeyed())
        assertNull(gate.onTimeout())
    }

    @Test
    fun `the last host's session replayed and then ended is dropped`() {
        // The bench: WIRELESS_CP (replay), the old phone's identity, its video key, then a live NONE 1.5 s later.
        gate.start()
        assertTrue(gate.holding)
        assertEquals(HelloReplay.Verdict.Hold, gate.onProjMode(Ocbm.PM_WIRELESS_CP, replay = true))
        assertFalse("the old phone's identity is held", gate.onIdentity("""{"deviceID":"aa"}"""))
        assertFalse("its video key does not say Phone connected", gate.onKeyed())
        assertTrue(gate.holding)
        val r = decided(gate.onProjMode(Ocbm.PM_NONE, replay = false))
        assertFalse(r.live)
        assertEquals(Ocbm.PM_NONE, r.mode)
        assertFalse(gate.holding)
        // The real reconnect, 15-20 s later, goes through untouched.
        assertEquals(HelloReplay.Verdict.Apply, gate.onProjMode(Ocbm.PM_WIRELESS_CP, replay = false))
        assertTrue(gate.onIdentity("""{"deviceID":"bb"}"""))
        assertTrue(gate.onKeyed())
    }

    @Test
    fun `a replayed session that stays up is released whole as live`() {
        gate.start()
        assertEquals(HelloReplay.Verdict.Hold, gate.onProjMode(Ocbm.PM_WIRELESS_CP, replay = true))
        assertFalse(gate.onIdentity("""{"deviceID":"aa"}"""))
        assertFalse(gate.onKeyed())
        val r = gate.onTimeout()!!
        assertTrue(r.live)
        assertEquals(Ocbm.PM_WIRELESS_CP, r.mode)
        assertEquals("""{"deviceID":"aa"}""", r.identity)
        assertTrue(r.keyed)
        assertFalse(gate.holding)
        assertNull("one release only", gate.onTimeout())
    }

    @Test
    fun `a live mode while holding releases what was held with that mode`() {
        gate.start()
        gate.onProjMode(Ocbm.PM_WIRELESS_CP, replay = true)
        gate.onKeyed()
        val r = decided(gate.onProjMode(Ocbm.PM_WIRED_CP, replay = false))
        assertTrue(r.live)
        assertEquals(Ocbm.PM_WIRED_CP, r.mode)
        assertTrue(r.keyed)
        assertNull(r.identity)
    }

    @Test
    fun `a replayed NONE has nothing to hold`() {
        gate.start()
        val r = decided(gate.onProjMode(Ocbm.PM_NONE, replay = true))
        assertTrue(r.live)
        assertEquals(Ocbm.PM_NONE, r.mode)
        assertFalse(gate.holding)
    }

    @Test
    fun `a box that marks nothing as replay decides at the first mode`() {
        // 1.0.1-1.0.4 send CT_PROJ_MODE without F_REPLAY: today's behaviour.
        gate.start()
        val r = decided(gate.onProjMode(Ocbm.PM_WIRELESS_CP, replay = false))
        assertTrue(r.live)
        assertEquals(Ocbm.PM_WIRELESS_CP, r.mode)
    }

    @Test
    fun `an identity before any mode is held and released with it`() {
        gate.start()
        assertFalse(gate.onIdentity("""{"deviceID":"aa"}"""))
        val r = decided(gate.onProjMode(Ocbm.PM_WIRED_CP, replay = false))
        assertEquals("""{"deviceID":"aa"}""", r.identity)
    }

    @Test
    fun `no mode at all by the hold time releases what came`() {
        gate.start()
        assertFalse(gate.onKeyed())
        val r = gate.onTimeout()!!
        assertTrue(r.live)
        assertNull(r.mode)
        assertTrue(r.keyed)
    }

    @Test
    fun `a new subscribe or a reset forgets what was held`() {
        gate.start()
        gate.onProjMode(Ocbm.PM_WIRELESS_CP, replay = true)
        gate.onIdentity("""{"deviceID":"aa"}""")
        gate.onKeyed()
        gate.start()
        val r = gate.onTimeout()!!
        assertNull(r.mode)
        assertNull(r.identity)
        assertFalse(r.keyed)
        gate.start()
        gate.reset()
        assertFalse(gate.holding)
        assertNull(gate.onTimeout())
    }
}
