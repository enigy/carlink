package com.carlink.av

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A focus loss or car stop right after a skip this app sent is the press's aftermath, not a source change. */
class SkipAftermathTest {
    @Test
    fun `nothing is covered before any skip`() {
        assertFalse(SkipAftermath().covers(5_000L))
    }

    @Test
    fun `the measured aftermath is covered, a loss later is not`() {
        val a = SkipAftermath()
        a.skipSent(10_000L)
        assertTrue("the radio's focus grab, 58 ms after the key", a.covers(10_058L))
        assertTrue("the car's stop, 430 ms after", a.covers(10_430L))
        assertTrue(a.covers(11_500L))
        assertFalse("a source change 2 s later is the driver's", a.covers(11_501L))
    }

    @Test
    fun `a new skip restarts the window`() {
        val a = SkipAftermath()
        a.skipSent(10_000L)
        a.skipSent(13_000L)
        assertFalse(a.covers(12_000L))
        assertTrue(a.covers(14_000L))
    }
}
