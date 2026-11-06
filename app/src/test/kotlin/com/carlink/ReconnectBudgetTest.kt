package com.carlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The reconnect chain's budget: its backoffs, its give-up, and that only a link that stayed up starts the next loss afresh. */
class ReconnectBudgetTest {
    private fun budget() = ReconnectBudget(max = 5, initialDelayMs = 2_000, maxDelayMs = 30_000, stableMs = STABLE)

    @Test
    fun `one loss backs off 2, 4, 8, 16 then 30 s, and the sixth attempt gives up and starts over`() {
        val b = budget()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L), List(5) { b.next(0) })
        assertEquals(5, b.used)
        assertNull("gave up", b.next(0))
        assertEquals("a tap after the give-up starts at attempt 1", 2_000L, b.next(0))
    }

    @Test
    fun `a loss after the link stayed up 30 s or more starts at 2 s, however many losses a drive has`() {
        val b = budget()
        var now = 0L
        // The bench, 6 Nov: a bridge kill took attempt 1, then the link stayed up 5 min 40 s before the unplug.
        repeat(8) { loss ->
            assertEquals("loss ${loss + 1} starts at 2 s", 2_000L, b.next(now))
            assertEquals(1, b.used)
            b.linkUp(now + 2_000) // CT_HELLO_ACK
            now += 2_000 + STABLE
        }
    }

    @Test
    fun `a link that answers HELLO and drops within 30 s keeps climbing and gives up after five`() {
        val b = budget()
        var now = 0L
        val backoffs =
            List(5) {
                b.next(now).also {
                    now += it!!
                    b.linkUp(now) // HELLO answered...
                    now += STABLE - 1 // ...and lost just short of stable
                }
            }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L), backoffs)
        assertNull("the box's escalation window is never hammered at 2 s", b.next(now))
    }

    @Test
    fun `losses with no link up between them give up after five, and reset starts over at once`() {
        val b = budget()
        repeat(5) { b.next(0) }
        assertNull(b.next(0))
        repeat(3) { b.next(0) }
        b.reset()
        assertEquals(2_000L, b.next(0))
    }

    private companion object {
        const val STABLE = 30_000L
    }
}
