package com.carlink.ledger

import com.carlink.ledger.ConnectRecord.EndReason
import com.carlink.ledger.ConnectRecord.Kind
import com.carlink.ledger.ConnectRecord.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ledger's cap and order, and the Settings figure's maths: which connects count, the median, the nearest-rank 90th. */
class ConnectLedgerTest {
    private fun wake(
        pictureMs: Long,
        at: Long = 1_000L,
        frontMs: Long? = 0L,
    ) = ConnectRecord(startedAtMs = at, kind = Kind.WAKE_LISTED, outcome = Outcome.PICTURE, endMs = pictureMs, pictureMs = pictureMs, starts = 1, frontMs = frontMs)

    /** A ledger of wakes with these pictures, oldest first. */
    private fun ledgerOf(vararg pictures: Long) = pictures.fold(ConnectLedger()) { l, ms -> l.with(wake(ms)) }

    @Test
    fun `the ledger keeps the last 200 connects, the oldest going first`() {
        var ledger = ConnectLedger()
        for (i in 1..ConnectLedger.CAP) ledger = ledger.with(wake(pictureMs = i.toLong(), at = i.toLong()))
        assertEquals(200, ledger.records.size)
        assertEquals(1L, ledger.records.first().startedAtMs)

        ledger = ledger.with(wake(pictureMs = 201, at = 201))
        assertEquals("still 200", 200, ledger.records.size)
        assertEquals("the oldest went", 2L, ledger.records.first().startedAtMs)
        assertEquals(201L, ledger.records.last().startedAtMs)
    }

    @Test
    fun `newest first is the order they were added, whatever their clock times say`() {
        // The head unit's clock can jump at boot: the order added is the truth, not the time each one names.
        val ledger = ConnectLedger().with(wake(3_000, at = 50_000)).with(wake(4_000, at = 10_000)).with(wake(5_000, at = 30_000))
        assertEquals(listOf(5_000L, 4_000L, 3_000L), ledger.newestFirst.map { it.pictureMs })
        assertEquals(listOf(3_000L, 4_000L, 5_000L), ledger.records.map { it.pictureMs })
    }

    @Test
    fun `only wakes that showed a picture count, the newest 20`() {
        var ledger = ConnectLedger()
        ledger = ledger.with(ConnectRecord(1, Kind.APP_START, Outcome.PICTURE, endMs = 30_000, pictureMs = 30_000))
        ledger = ledger.with(ConnectRecord(2, Kind.RECONNECT, Outcome.PICTURE, endMs = 2_000, pictureMs = 2_000))
        ledger = ledger.with(ConnectRecord(3, Kind.WAKE_LISTED, Outcome.NO_PHONE, endMs = ConnectTimer.WINDOW_MS))
        ledger = ledger.with(ConnectRecord(4, Kind.WAKE_LISTED, Outcome.ENDED_EARLY, endMs = 900, endReason = EndReason.DISPLAY_OFF))
        ledger = ledger.with(ConnectRecord(5, Kind.WAKE_NOT_LISTED, Outcome.PICTURE, endMs = 9_000, pictureMs = 9_000, frontMs = 0))
        ledger = ledger.with(wake(4_000))
        assertEquals("newest first, wakes with a picture only", listOf(4_000L, 9_000L), ConnectStats.wakePictures(ledger))

        val many = (1..25).fold(ConnectLedger()) { l, i -> l.with(wake(i * 100L)) }
        assertEquals((25 downTo 6).map { it * 100L }, ConnectStats.wakePictures(many))
    }

    @Test
    fun `a wake whose screen wasn't in front within 1 s of the display on is left out of the figure, and kept`() {
        val ledger =
            ConnectLedger()
                .with(wake(3_000, frontMs = 0))
                .with(wake(3_100, frontMs = 1_000))
                .with(wake(42_000, frontMs = 40_500)) // the driver opened Carlink 40 s after the wake
                .with(wake(3_300, frontMs = 1_001))
                .with(wake(3_400, frontMs = null))
        assertEquals(listOf(3_100L, 3_000L), ConnectStats.wakePictures(ledger))
        assertEquals("kept all the same", 5, ledger.records.size)
        assertEquals(listOf(false, false, true, true, true), ledger.records.map(ConnectStats::leftOutOfFigure))

        // Only a wake's picture is left out: the others never counted, and aren't marked.
        val others =
            listOf(
                ConnectRecord(1, Kind.APP_START, Outcome.PICTURE, endMs = 30_000, pictureMs = 30_000),
                ConnectRecord(2, Kind.WAKE_LISTED, Outcome.NOT_IN_FRONT, endMs = ConnectTimer.WINDOW_MS),
                ConnectRecord(3, Kind.WAKE_LISTED, Outcome.NO_PHONE, endMs = ConnectTimer.WINDOW_MS),
            )
        assertTrue(others.none(ConnectStats::leftOutOfFigure))
    }

    @Test
    fun `a late screen doesn't move the median or the 9 in 10 time`() {
        val ledger = ledgerOf(3_000, 3_100, 3_200, 3_300, 3_400).with(wake(42_000, frontMs = 40_500))
        val s = ConnectStats.summary(ledger)!!
        assertEquals(5, s.count)
        assertEquals(3_200L, s.medianMs)
        assertEquals(3_400L, s.ninetiethMs)
    }

    @Test
    fun `fewer than 5 such connects give no figure`() {
        assertNull(ConnectStats.summary(ledgerOf()))
        assertNull(ConnectStats.summary(ledgerOf(1_000, 2_000, 3_000, 4_000)))
        // Four wakes with a picture and others that don't count are still four.
        val mixed = ledgerOf(1_000, 2_000, 3_000, 4_000).with(ConnectRecord(9, Kind.APP_START, Outcome.PICTURE, endMs = 1, pictureMs = 1))
        assertNull(ConnectStats.summary(mixed))
    }

    @Test
    fun `with 5 connects the 90th percentile is the slowest`() {
        val s = ConnectStats.summary(ledgerOf(5_000, 1_000, 4_000, 2_000, 3_000))!!
        assertEquals(5, s.count)
        assertEquals(3_000L, s.medianMs)
        assertEquals("rank ceil(0.9 x 5) = 5", 5_000L, s.ninetiethMs)
    }

    @Test
    fun `with 19 connects the 90th percentile is the 18th fastest`() {
        val s = ConnectStats.summary(ledgerOf(*(19 downTo 1).map { it * 1_000L }.toLongArray()))!!
        assertEquals(19, s.count)
        assertEquals("the 10th of 19", 10_000L, s.medianMs)
        assertEquals("rank ceil(0.9 x 19) = 18", 18_000L, s.ninetiethMs)
    }

    @Test
    fun `with 20 connects the median is between the middle two and the 90th percentile is the 18th`() {
        val s = ConnectStats.summary(ledgerOf(*(1..20).map { it * 1_000L }.toLongArray()))!!
        assertEquals(20, s.count)
        assertEquals("the 10th and 11th, averaged", 10_500L, s.medianMs)
        assertEquals("rank ceil(0.9 x 20) = 18", 18_000L, s.ninetiethMs)
    }

    @Test
    fun `with 21 connects only the newest 20 count`() {
        // Oldest first: the 100 s one is the oldest and drops out of the 20.
        val s = ConnectStats.summary(ledgerOf(100_000L, *(1..20).map { it * 1_000L }.toLongArray()))!!
        assertEquals(20, s.count)
        assertEquals(10_500L, s.medianMs)
        assertEquals(18_000L, s.ninetiethMs)

        // The other way round, the newest is the slow one and the oldest 1 s one drops out.
        val t = ConnectStats.summary(ledgerOf(*(1..20).map { it * 1_000L }.toLongArray(), 100_000L))!!
        assertEquals(20, t.count)
        assertEquals("the 11th and 12th of 2..20 s and 100 s", 11_500L, t.medianMs)
        assertEquals("the 18th: 19 s", 19_000L, t.ninetiethMs)
    }

    @Test
    fun `the median rounds half up, and one or two values are their own median`() {
        assertEquals(1_001L, ConnectStats.median(listOf(1_000L, 1_001L)))
        assertEquals(7L, ConnectStats.median(listOf(7L)))
        assertEquals(7L, ConnectStats.nearestRank(listOf(7L), 90))
        assertEquals(2L, ConnectStats.nearestRank(listOf(1L, 2L), 90))
        assertEquals(1L, ConnectStats.nearestRank(listOf(1L, 2L), 50))
    }

    @Test
    fun `seconds have one decimal, rounded half up`() {
        assertEquals("3.4 s", ConnectStats.seconds(3_449))
        assertEquals("3.5 s", ConnectStats.seconds(3_450))
        assertEquals("0.0 s", ConnectStats.seconds(0))
        assertEquals("12.0 s", ConnectStats.seconds(11_960))
        assertEquals("120.0 s", ConnectStats.seconds(119_999))
    }
}
