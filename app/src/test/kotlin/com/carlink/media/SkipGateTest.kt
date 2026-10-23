package com.carlink.media

import com.carlink.media.SkipGate.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One skip per steering-wheel press, whichever way the press reaches the app. */
class SkipGateTest {
    private val gate = SkipGate(windowMs = 500)

    @Test
    fun aPressArrivingBothWaysSkipsOnce() {
        assertTrue(gate.allow(next = true, Path.COMMAND, nowMs = 1_000))
        assertFalse("the raw key for the same press", gate.allow(next = true, Path.KEY, nowMs = 1_120))
        assertTrue("the next press", gate.allow(next = true, Path.KEY, nowMs = 2_000))
    }

    @Test
    fun aQuickDoublePressStillSkipsTwice() {
        assertTrue(gate.allow(next = true, Path.KEY, nowMs = 1_000))
        assertTrue(gate.allow(next = true, Path.KEY, nowMs = 1_200))
    }

    @Test
    fun backRightAfterNextIsItsOwnPress() {
        assertTrue(gate.allow(next = true, Path.COMMAND, nowMs = 1_000))
        assertTrue(gate.allow(next = false, Path.KEY, nowMs = 1_100))
    }

    @Test
    fun outsideTheWindowBothCount() {
        assertTrue(gate.allow(next = true, Path.KEY, nowMs = 1_000))
        assertTrue(gate.allow(next = true, Path.COMMAND, nowMs = 1_600))
    }
}
