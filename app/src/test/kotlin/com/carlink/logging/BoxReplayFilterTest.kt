package com.carlink.logging

import com.carlink.ocbm.BoxLogEntry
import com.carlink.ocbm.Ocbm
import org.junit.Assert.assertEquals
import org.junit.Test

class BoxReplayFilterTest {
    private fun line(
        ms: Long,
        backfill: Boolean,
        source: Int = Ocbm.LOG_SRC_BOX,
    ) = BoxLogEntry(source, if (backfill) Ocbm.LOG_F_BACKFILL else 0, 0, ms, "t$ms")

    @Test
    fun `a reconnect's replay skips what was already written and keeps what is new`() {
        val newest = HashMap<Int, Long>()
        val first = listOf(line(10, true), line(20, true), line(30, false))
        val second = listOf(line(10, true), line(20, true), line(30, true), line(40, true), line(50, false))
        val kept = (first + second).filter { BoxReplayFilter.keep(newest, it) }.map { it.unixMs }
        assertEquals(listOf(10L, 20L, 30L, 40L, 50L), kept)
    }

    @Test
    fun `sources are tracked separately and live lines always pass`() {
        val newest = HashMap<Int, Long>()
        val entries =
            listOf(
                line(100, false),
                line(50, true, Ocbm.LOG_SRC_IAP2D),
                line(90, false),
            )
        assertEquals(3, entries.count { BoxReplayFilter.keep(newest, it) })
    }
}
