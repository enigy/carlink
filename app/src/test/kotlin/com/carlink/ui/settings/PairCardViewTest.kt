package com.carlink.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' pairing card follows the box's BH_PAIR_WINDOW, not the tap alone (9 Nov bench). */
class PairCardViewTest {
    private val ends = 1_000L + PAIR_WINDOW_SECONDS * 1_000L

    @Test
    fun `a tap shows the window open until the box answers`() {
        val v = pairCardView(boxSaysOpen = false, bluetoothStarting = false, tappedAt = 1_000, stoppedAt = 0, endsAt = ends, now = 3_000)
        assertTrue(v.open)
        assertFalse(v.unanswered)
        assertEquals(PAIR_WINDOW_SECONDS - 2L, v.secondsLeft)
    }

    @Test
    fun `a tap the box never answers is closed again, and says so`() {
        // The bench: btd refused the open and the card counted down for two minutes, so the next tap was Stop.
        val v = pairCardView(false, false, tappedAt = 1_000, stoppedAt = 0, endsAt = ends, now = 1_000 + OPEN_CONFIRM_MS + 1)
        assertFalse(v.open)
        assertTrue(v.unanswered)
        assertFalse(v.ticking)
    }

    @Test
    fun `a tap waits as long as the adapter's Bluetooth is starting`() {
        val v = pairCardView(false, bluetoothStarting = true, tappedAt = 1_000, stoppedAt = 0, endsAt = ends, now = 20_000)
        assertTrue(v.open)
        assertFalse(v.unanswered)
    }

    @Test
    fun `the box's open window is shown open, even one this card didn't open`() {
        val v = pairCardView(boxSaysOpen = true, bluetoothStarting = false, tappedAt = 0, stoppedAt = 0, endsAt = 0, now = 5_000)
        assertTrue(v.open)
        assertEquals(PAIR_WINDOW_SECONDS.toLong(), v.secondsLeft)
        assertTrue(v.ticking)
    }

    @Test
    fun `the box closing it closes the card`() {
        val v = pairCardView(boxSaysOpen = false, bluetoothStarting = false, tappedAt = 0, stoppedAt = 0, endsAt = 0, now = 9_000)
        assertFalse(v.open)
        assertFalse(v.unanswered)
        assertFalse(v.ticking)
    }

    @Test
    fun `stop shows closed at once, and open again if the box still says so`() {
        val stopping = pairCardView(boxSaysOpen = true, bluetoothStarting = false, tappedAt = 0, stoppedAt = 10_000, endsAt = ends, now = 11_000)
        assertFalse(stopping.open)
        assertTrue("it looks again", stopping.ticking)
        val stillOpen = pairCardView(true, false, tappedAt = 0, stoppedAt = 10_000, endsAt = ends, now = 10_000 + OPEN_CONFIRM_MS + 1)
        assertTrue(stillOpen.open)
    }
}
