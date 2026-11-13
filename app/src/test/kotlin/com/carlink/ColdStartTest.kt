package com.carlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColdStartTest {
    @Test
    fun `one line per process places each stage from the process start`() {
        val s = ColdStart(processStartMs = 41_230)
        s.adapterSeen(43_000)
        s.adapterSeen(50_000)
        s.bridgeOpen(43_400)
        assertEquals(
            "[startup] carlink: head unit up 41.2 s at the process start; adapter seen +1770 ms, bridge open +2170 ms, " +
                "SUBSCRIBE +2570 ms",
            s.subscribed(43_800),
        )
        assertNull("once per process", s.subscribed(60_000))
    }

    @Test
    fun `a stage not seen shows a dash`() {
        assertEquals(
            "[startup] carlink: head unit up 5.0 s at the process start; adapter seen -, bridge open -, SUBSCRIBE +100 ms",
            ColdStart(5_000).subscribed(5_100),
        )
    }
}
