package com.carlink.ui.settings

import com.carlink.ledger.ConnectLedger
import com.carlink.ledger.ConnectRecord
import com.carlink.ledger.ConnectRecord.Kind
import com.carlink.ledger.ConnectRecord.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings › Adapter and about › Connect time, in each state: being read, not enough connects yet, and the figure. */
class ConnectTimeTextTest {
    private fun ledgerOf(vararg pictures: Long) =
        pictures.fold(ConnectLedger()) { l, ms ->
            l.with(ConnectRecord(startedAtMs = 1, kind = Kind.WAKE_LISTED, outcome = Outcome.PICTURE, endMs = ms, pictureMs = ms, starts = 1))
        }

    @Test
    fun `while the file is read it says so`() {
        assertEquals("Reading…", ConnectTimeText.subtitle(null))
    }

    @Test
    fun `with fewer than 5 connects it says there are not enough yet`() {
        assertEquals(
            "Not enough connects yet (0 of 5). Each is timed from the display coming on to the CarPlay picture.",
            ConnectTimeText.subtitle(ConnectLedger()),
        )
        assertEquals(
            "Not enough connects yet (4 of 5). Each is timed from the display coming on to the CarPlay picture.",
            ConnectTimeText.subtitle(ledgerOf(3_000, 3_100, 3_200, 3_300)),
        )
    }

    @Test
    fun `with 5 or more it gives the median and the time 9 in 10 were within, in seconds`() {
        assertEquals(
            "From the display coming on to the CarPlay picture, over the last 5 connects: half within 3.2 s, 9 in 10 within 9.8 s.",
            ConnectTimeText.subtitle(ledgerOf(3_000, 3_100, 3_240, 3_300, 9_750)),
        )
        // 2.1 to 4.5 s, oldest first: the newest 20 are 2.6 to 4.5 s, median 3.55 s, the 18th 4.3 s.
        val twentyFive = ledgerOf(*(1..25).map { 2_000L + it * 100L }.toLongArray())
        assertEquals(
            "From the display coming on to the CarPlay picture, over the last 20 connects: half within 3.6 s, 9 in 10 within 4.3 s.",
            ConnectTimeText.subtitle(twentyFive),
        )
    }
}
