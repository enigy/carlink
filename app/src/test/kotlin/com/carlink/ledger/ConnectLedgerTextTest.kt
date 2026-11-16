package com.carlink.ledger

import com.carlink.ledger.ConnectRecord.EndReason
import com.carlink.ledger.ConnectRecord.Kind
import com.carlink.ledger.ConnectRecord.Outcome
import com.carlink.logging.LogRedactor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/** The ledger as Save Logs writes it: a header, then one readable line per connect, newest first. */
class ConnectLedgerTextTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-11-16 07:42:13 UTC
    private val at = 1_794_814_933_000L

    private val wake =
        ConnectRecord(
            startedAtMs = at,
            kind = Kind.WAKE_LISTED,
            outcome = Outcome.PICTURE,
            endMs = 3_412,
            subscribeMs = 612,
            pictureMs = 3_412,
            streamingMs = 4_105,
            starts = 1,
            frontMs = 214,
            suspendedMs = 412_300,
            adapter = AdapterStartup(uptimeMs = 1_802_400, preload = "done", absentPages = 0, resets = 0, firstNudges = 0, secondNudges = 0, pskMismatches = 0),
            timeline = "[timeline] v1 a=1 b=-",
        )

    @Test
    fun `a wake that showed a picture reads as one line`() {
        assertEquals(
            "2026-11-16 07:42:13 wake, adapter listed: picture +3412 ms | SUBSCRIBE +612 ms, picture +3412 ms, STREAMING +4105 ms | " +
                "1 start, screen in front +214 ms, suspended 412300 ms while off | adapter up 1802.4 s, preload done, absent pages 0, " +
                "resets 0, nudges 0+0, PSK mismatches 0 | [timeline] v1 a=1 b=-",
            ConnectLedgerText.line(wake, utc),
        )
    }

    @Test
    fun `a picture's video in and decoder ready follow SUBSCRIBE, each only when the record has it`() {
        val measured = wake.copy(videoInMs = 3_190, decoderReadyMs = 905)
        assertEquals(
            "SUBSCRIBE +612 ms, video in +3190 ms, decoder ready +905 ms, picture +3412 ms, STREAMING +4105 ms",
            ConnectLedgerText.line(measured, utc).split(" | ")[1],
        )
        assertEquals(
            "a decoder built at the first frame, its ready time not kept",
            "SUBSCRIBE +612 ms, video in +3190 ms, picture +3412 ms, STREAMING +4105 ms",
            ConnectLedgerText.line(wake.copy(videoInMs = 3_190), utc).split(" | ")[1],
        )
        assertEquals(
            "a record kept before they were measured reads as it always did",
            "SUBSCRIBE +612 ms, picture +3412 ms, STREAMING +4105 ms",
            ConnectLedgerText.line(wake, utc).split(" | ")[1],
        )
    }

    @Test
    fun `steps that never came are dashes, and each ending says why`() {
        val off = ConnectRecord(at, Kind.WAKE_NOT_LISTED, Outcome.ENDED_EARLY, endMs = 2_100, endReason = EndReason.DISPLAY_OFF, starts = 1, suspendedMs = 0)
        assertEquals(
            "2026-11-16 07:42:13 wake, adapter not listed: ended early, the display off again, +2100 ms | SUBSCRIBE -, picture -, STREAMING - | " +
                "1 start, screen not in front, not suspended | no adapter line | no timeline",
            ConnectLedgerText.line(off, utc),
        )
        val noPhone = ConnectRecord(at, Kind.APP_START, Outcome.NO_PHONE, endMs = ConnectTimer.WINDOW_MS, subscribeMs = 10_763, starts = 1, frontMs = 0)
        assertEquals(
            "2026-11-16 07:42:13 app start: no phone, no picture within 120 s | SUBSCRIBE +10763 ms, picture -, STREAMING - | " +
                "1 start, screen in front from the origin | no adapter line | no timeline",
            ConnectLedgerText.line(noPhone, utc),
        )
        val error = ConnectRecord(at, Kind.RECONNECT, Outcome.ENDED_EARLY, endMs = 64_500, endReason = EndReason.ERROR, starts = 2)
        assertEquals(
            "2026-11-16 07:42:13 reconnect: ended early, an error, +64500 ms | SUBSCRIBE -, picture -, STREAMING - | 2 starts, screen not in front " +
                "| no adapter line | no timeline",
            ConnectLedgerText.line(error, utc),
        )
    }

    @Test
    fun `an adapter line's missing figures are dashes and a cut count a lower bound`() {
        val r = wake.copy(adapter = AdapterStartup(preload = "running", pskMismatches = 3, pskAtLeast = true))
        assertEquals(
            "adapter up -, preload running, absent pages -, resets -, nudges -, PSK mismatches >=3",
            ConnectLedgerText.line(r, utc).split(" | ")[3],
        )
    }

    @Test
    fun `the saved text has its header, then the connects newest first, and redaction leaves it as it is`() {
        val ledger = (1..5).fold(ConnectLedger()) { l, i -> l.with(wake.copy(startedAtMs = at + i * 60_000L, pictureMs = i * 1_000L, endMs = i * 1_000L)) }
        val text = ConnectLedgerText.text(ledger, utc)
        val lines = text.trimEnd('\n').split('\n')
        assertEquals(7, lines.size)
        assertEquals(
            "Carlink connect ledger: 5 connects (the last 200 are kept), newest first. Times are from each one's origin: the display on " +
                "for a wake, the process start for an app start, the start itself for a reconnect.",
            lines[0],
        )
        assertEquals("Display on to picture, the last 5 wakes with a picture: median 3000 ms, 9 in 10 within 5000 ms (nearest rank).", lines[1])
        assertEquals(listOf("07:47:13", "07:46:13", "07:45:13", "07:44:13", "07:43:13"), lines.drop(2).map { it.substring(11, 19) })
        assertEquals(text, String(LogRedactor.text(text.toByteArray(Charsets.UTF_8)), Charsets.UTF_8))
    }

    @Test
    fun `with too few wakes the header says so`() {
        assertEquals("Display on to picture: not enough wakes with a picture yet (1 of 5).", ConnectLedgerText.summaryLine(ConnectLedger().with(wake)))
    }
}
