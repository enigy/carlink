package com.carlink

import com.carlink.DisplayOff.WakePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wake path (the Mac's 13 Nov brief, item 7): its choice, its hello, its line, a cancelled start, the preload line. */
class WakeConnectTest {
    @Test
    fun `a listed adapter is opened at once, otherwise after the settle`() {
        assertEquals(WakePath.AT_ONCE, DisplayOff.firstPath(adapterListed = true))
        assertEquals(WakePath.NOT_LISTED, DisplayOff.firstPath(adapterListed = false))
    }

    @Test
    fun `only the wake's open at once waits 3 s for the first reply`() {
        assertEquals(3_000L, DisplayOff.helloMs(wakeOpen = true))
        assertEquals(20_000L, DisplayOff.helloMs(wakeOpen = false))
    }

    @Test
    fun `the wake line names how it connected, and none for a wake with no phone for minutes`() {
        assertEquals(
            "[startup] wake: display on to STREAMING 14915 ms (at once, the adapter listed)",
            DisplayOff.wakeLine(14_915, WakePath.AT_ONCE),
        )
        assertEquals(
            "[startup] wake: display on to STREAMING 9000 ms (the open at once got no reply in 3 s, then the retry)",
            DisplayOff.wakeLine(9_000, WakePath.HELLO_MISSED),
        )
        assertTrue(DisplayOff.wakeLine(20_000, WakePath.OPEN_FAILED)!!.endsWith("(after the settle, the open at once failed)"))
        assertTrue(DisplayOff.wakeLine(20_000, WakePath.NOT_LISTED)!!.endsWith("(after the settle, the adapter not listed)"))
        assertNull(DisplayOff.wakeLine(DisplayOff.WAKE_TIMED_MAX_MS, WakePath.AT_ONCE))
    }

    @Test
    fun `a start cancelled while connecting goes back to DISCONNECTED`() =
        runBlocking {
            var connecting = false
            var undone = false
            val started = CompletableDeferred<Unit>()
            val start =
                async {
                    val undo = {
                        undone = true
                        connecting = false
                    }
                    undoCancelledStart(stuck = { connecting }, undo = undo) {
                        connecting = true
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            started.await()
            start.cancel()
            try {
                start.await()
            } catch (_: CancellationException) {
            }
            assertTrue("undone", undone)
            assertFalse(connecting)
        }

    @Test
    fun `a start cancelled after the session came up is left alone`() =
        runBlocking {
            var undone = false
            val start = async { undoCancelledStart(stuck = { false }, undo = { undone = true }) { awaitCancellation() } }
            start.cancel()
            try {
                start.await()
            } catch (_: CancellationException) {
            }
            assertFalse(undone)
        }

    @Test
    fun `the preload line is logged once per session, and on a change`() {
        val note = PreloadNote()
        assertEquals("[ADAPTER] boot radio preload on", note.line("on"))
        assertNull("polled again", note.line("on"))
        assertNull("an older adapter says nothing", note.line(""))
        assertEquals(
            "[ADAPTER] boot radio preload off_guard: its guard switched it off after unfinished preloads",
            note.line("off_guard"),
        )
        note.newSession()
        assertEquals("[ADAPTER] boot radio preload off_guard: its guard switched it off after unfinished preloads", note.line("off_guard"))
    }
}
