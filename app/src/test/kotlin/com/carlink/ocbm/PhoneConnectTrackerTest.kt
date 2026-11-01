package com.carlink.ocbm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Connect's feedback (owner, 11-01): working at once, then connected or not reached, presses ignored meanwhile. */
class PhoneConnectTrackerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var live = LivePhone(null, 0)
    private val timing = PhoneConnectTracker.Timing(waitMs = 600, pollMs = 20, outcomeMs = 300)
    private val tracker = PhoneConnectTracker(scope, { live }, timing)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a press works at once, and others are ignored until it ends`() {
        assertTrue(tracker.start("AA:BB:CC:00:6A:DE"))
        assertEquals(ConnectAttempt("aa:bb:cc:00:6a:de"), tracker.attempt.value)
        assertTrue(tracker.attempt.value!!.working)
        assertFalse("a second press while it works", tracker.start("68:A7:29:00:25:10"))
        assertFalse(tracker.start("AA:BB:CC:00:6A:DE"))
    }

    @Test
    fun `the phone named live after the press is connected, and the outcome stands a while`() {
        tracker.start("68:A7:29:00:25:10")
        live = LivePhone("68:a7:29:00:25:10", 1)
        val done = waitFor { tracker.attempt.value?.outcome != null }
        assertEquals(ConnectAttempt.Outcome.CONNECTED, done?.outcome)
        waitFor { tracker.attempt.value == null }
        assertTrue("then Connect works again", tracker.start("68:A7:29:00:25:10"))
    }

    @Test
    fun `a stale identity is not news, a new one for the same phone is`() {
        // A fresh Carlink is replayed the last session's phone before any phone connects (11-01).
        live = LivePhone("68:a7:29:00:25:10", 1)
        tracker.start("68:A7:29:00:25:10")
        Thread.sleep(150)
        assertTrue("still working on the stale name", tracker.attempt.value!!.working)
        live = LivePhone("68:a7:29:00:25:10", 2)
        assertEquals(ConnectAttempt.Outcome.CONNECTED, waitFor { tracker.attempt.value?.outcome != null }?.outcome)
    }

    @Test
    fun `no phone within the hold, or a refusal, is not reached`() {
        tracker.start("AA:BB:CC:00:6A:DE")
        assertEquals(ConnectAttempt.Outcome.NOT_REACHED, waitFor(2_000) { tracker.attempt.value?.outcome != null }?.outcome)
        waitFor { tracker.attempt.value == null }
        tracker.start("AA:BB:CC:00:6A:DE")
        tracker.refused("aa:bb:cc:00:6a:de")
        assertEquals("at once", ConnectAttempt.Outcome.NOT_REACHED, tracker.attempt.value?.outcome)
        tracker.refused("68:A7:29:00:25:10")
        assertEquals("another phone's refusal changes nothing", ConnectAttempt.Outcome.NOT_REACHED, tracker.attempt.value?.outcome)
        assertNull(waitFor { tracker.attempt.value == null })
    }

    private fun waitFor(
        timeoutMs: Long = 1_000,
        done: () -> Boolean,
    ): ConnectAttempt? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > deadline) fail("timed out; ${tracker.attempt.value}")
            Thread.sleep(10)
        }
        return tracker.attempt.value
    }
}
