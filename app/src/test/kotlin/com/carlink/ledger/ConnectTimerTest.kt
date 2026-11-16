package com.carlink.ledger

import com.carlink.ledger.ConnectRecord.EndReason
import com.carlink.ledger.ConnectRecord.Kind
import com.carlink.ledger.ConnectRecord.Origin
import com.carlink.ledger.ConnectRecord.Outcome
import com.carlink.ledger.ConnectTimer.Companion.SETTLE_MS
import com.carlink.ledger.ConnectTimer.Companion.WINDOW_MS
import com.carlink.ocbm.BoxLogEntry
import com.carlink.ocbm.Ocbm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The connect timer driven event by event as CarlinkManager reports them, with made-up times: each kind and origin, the
 * head unit's sleep while the display was off, and every way a connect ends.
 */
class ConnectTimerTest {
    private val committed = mutableListOf<ConnectRecord>()
    private val scheduled = mutableListOf<Long>()
    private val notes = mutableListOf<String>()
    private val wall = 1_795_330_000_000L
    private val begun = AtomicBoolean(false)

    /** A timer whose screen has been in front since [frontSince] (as CarlinkManager tells it when built), or not, with null. */
    private fun timer(
        processStartMs: Long = 11_100L,
        frontSince: Long? = 0L,
    ) = ConnectTimer(
        processStartMs = processStartMs,
        commit = { committed += it },
        schedule = { scheduled += it },
        wallClock = { wall },
        processBegun = begun,
        note = { notes += it },
    ).also { t -> frontSince?.let { t.appVisible(true, it) } }

    /** The display off at 100 s and on at [onAt]; uptime moved [awakeMs] of it, so the rest was sleep. */
    private fun ConnectTimer.offThenOn(
        onAt: Long,
        awakeMs: Long = onAt - 100_000L,
    ) {
        displayOff(100_000L, 90_000L)
        displayOn(onAt, 90_000L + awakeMs)
    }

    private fun box(
        text: String,
        flags: Int = 0,
    ) = BoxLogEntry(source = Ocbm.LOG_SRC_AIRPLAYD_WL, flags = flags, seq = 1, unixMs = 0L, text = text)

    private val startupLine =
        "[startup] host present +0 ms (adapter up 1802.4 s, preload done), Bluetooth up +1195 ms, phone connected +2088 ms, " +
            "Wi-Fi handoff +4034 ms, first frame +7195 ms; since the host came: absent pages 0, resets 0 (0 cut, no SDP channel), " +
            "PSK mismatches 0; this session: nudges 0+0"

    private val timelineLine = "[timeline] v1 host_present=0 bt_up=1195 phone=2088 handoff=4034 first_frame=7195"

    @Test
    fun `a wake with the adapter listed is timed from the display on through SUBSCRIBE, picture and STREAMING`() {
        val t = timer()
        t.offThenOn(onAt = 700_000L, awakeMs = 100_000L)
        t.wake(displayOnMs = 700_000L, adapterListed = true, nowMs = 700_010L)
        t.startBegan(700_020L)
        t.subscribed(700_650L)
        t.picture(atMs = 703_400L, nowMs = 703_450L)
        t.streaming(704_100L)
        assertTrue("held for STREAMING and the adapter's lines", committed.isEmpty())
        t.tick(703_400L + SETTLE_MS)

        val r = committed.single()
        assertEquals(Kind.WAKE_LISTED, r.kind)
        assertEquals(Origin.DISPLAY_ON, r.origin)
        assertEquals("the display on, on the wall clock", wall - 10L, r.startedAtMs)
        assertEquals(650L, r.subscribeMs)
        assertEquals(3_400L, r.pictureMs)
        assertEquals(4_100L, r.streamingMs)
        assertEquals(1, r.starts)
        assertEquals("600 s off, 100 s of it awake", 500_000L, r.suspendedMs)
        assertEquals(Outcome.PICTURE, r.outcome)
        assertEquals(3_400L, r.endMs)
        assertNull(r.endReason)
        assertEquals("in front all along", 0L, r.frontMs)
    }

    @Test
    fun `a wake with the adapter not listed is its own kind`() {
        val t = timer()
        t.offThenOn(onAt = 200_000L)
        t.wake(200_000L, adapterListed = false, nowMs = 200_000L)
        t.startBegan(202_500L)
        t.subscribed(203_100L)
        t.picture(209_000L, 209_000L)
        t.displayOff(215_000L, 0L)
        val r = committed.single()
        assertEquals(Kind.WAKE_NOT_LISTED, r.kind)
        assertEquals(Origin.DISPLAY_ON, r.origin)
        assertEquals(9_000L, r.pictureMs)
        assertEquals("awake all the time it was off", 0L, r.suspendedMs)
    }

    @Test
    fun `the process's first start is an app start, timed from the process start`() {
        val t = timer(processStartMs = 11_100L)
        t.startBegan(15_000L)
        t.subscribed(21_863L)
        t.picture(33_000L, 33_000L)
        t.closing(34_000L)
        val r = committed.single()
        assertEquals(Kind.APP_START, r.kind)
        assertEquals(Origin.PROCESS_START, r.origin)
        assertEquals("the process start, on the wall clock", wall - (15_000L - 11_100L), r.startedAtMs)
        assertEquals(10_763L, r.subscribeMs)
        assertEquals(21_900L, r.pictureMs)
        assertNull("only a wake's", r.suspendedMs)
        assertEquals(Outcome.PICTURE, r.outcome)
    }

    @Test
    fun `any other start with none in flight is a reconnect, timed from that start`() {
        val t = timer()
        t.startBegan(15_000L)
        t.subscribed(16_000L)
        t.picture(18_000L, 18_000L)
        t.sessionDown(500_000L) // the picture's connect is over: kept as it was
        t.startBegan(502_000L)
        t.subscribed(502_400L)
        t.picture(504_000L, 504_000L)
        t.closing(510_000L)
        assertEquals(listOf(Kind.APP_START, Kind.RECONNECT), committed.map { it.kind })
        val r = committed[1]
        assertEquals(Origin.RECONNECT, r.origin)
        assertEquals(wall, r.startedAtMs)
        assertEquals(400L, r.subscribeMs)
        assertEquals(2_000L, r.pictureMs)
    }

    @Test
    fun `a first start long after the process began is a reconnect, not an app start`() {
        // The media card's service started the process at boot; the screen opened minutes later.
        val t = timer(processStartMs = 11_100L)
        t.startBegan(11_100L + WINDOW_MS)
        t.closing(11_100L + WINDOW_MS + 1_000L)
        assertEquals(Kind.RECONNECT, committed.single().kind)
    }

    @Test
    fun `a wake first in the process leaves no app start for later`() {
        val t = timer(processStartMs = 1_000L)
        t.offThenOn(onAt = 105_000L)
        t.wake(105_000L, adapterListed = true, nowMs = 105_000L)
        t.displayOff(106_000L, 0L)
        t.displayOn(107_000L, 0L)
        t.startBegan(107_100L) // not a wake's (none was launched): a reconnect, not an app start
        t.closing(108_000L)
        assertEquals(listOf(Kind.WAKE_LISTED, Kind.RECONNECT), committed.map { it.kind })
    }

    @Test
    fun `sleep is the gap between the two clocks, and a few milliseconds of it are none`() {
        assertEquals(500_000L, ConnectTimer.suspendedMs(elapsedMs = 600_000L, uptimeMs = 100_000L))
        assertEquals("the clocks read one after the other", 0L, ConnectTimer.suspendedMs(elapsedMs = 60_000L, uptimeMs = 59_950L))
        assertEquals(100L, ConnectTimer.suspendedMs(elapsedMs = 60_000L, uptimeMs = 59_900L))
        assertEquals(0L, ConnectTimer.suspendedMs(elapsedMs = 60_000L, uptimeMs = 60_000L))
    }

    @Test
    fun `a wake whose display off Carlink never saw has no sleep figure`() {
        val t = timer()
        t.displayOn(50_000L, 40_000L)
        t.wake(50_000L, adapterListed = true, nowMs = 50_000L)
        t.closing(51_000L)
        assertNull(committed.single().suspendedMs)
    }

    @Test
    fun `no picture within the window while the session is up is no phone`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.startBegan(300_010L)
        t.subscribed(300_600L)
        t.tick(300_000L + WINDOW_MS - 1)
        assertTrue(committed.isEmpty())
        t.tick(300_000L + WINDOW_MS)
        val r = committed.single()
        assertEquals(Outcome.NO_PHONE, r.outcome)
        assertEquals(WINDOW_MS, r.endMs)
        assertNull(r.pictureMs)
        assertEquals(600L, r.subscribeMs)
    }

    @Test
    fun `a picture at the end of the window comes too late`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.startBegan(300_010L)
        t.subscribed(300_600L)
        t.picture(atMs = 300_000L + WINDOW_MS, nowMs = 300_000L + WINDOW_MS)
        assertEquals(Outcome.NO_PHONE, committed.single().outcome)
    }

    @Test
    fun `a picture just inside the window, reported after it, still counts`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.startBegan(300_010L)
        t.subscribed(300_600L)
        t.picture(atMs = 300_000L + WINDOW_MS - 1, nowMs = 300_000L + WINDOW_MS + 80L)
        t.tick(300_000L + WINDOW_MS + SETTLE_MS)
        assertEquals(Outcome.PICTURE, committed.single().outcome)
    }

    @Test
    fun `the display going off again before the picture ends it early`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.startBegan(300_010L)
        t.displayOff(302_100L, 0L)
        val r = committed.single()
        assertEquals(Outcome.ENDED_EARLY, r.outcome)
        assertEquals(EndReason.DISPLAY_OFF, r.endReason)
        assertEquals(2_100L, r.endMs)
        assertNull(r.subscribeMs)
    }

    @Test
    fun `Close app or a handover before the picture ends it early`() {
        val t = timer()
        t.startBegan(15_000L)
        t.subscribed(16_000L)
        t.closing(20_000L)
        val r = committed.single()
        assertEquals(Outcome.ENDED_EARLY, r.outcome)
        assertEquals(EndReason.APP_CLOSED, r.endReason)
        assertEquals(20_000L - 11_100L, r.endMs)
    }

    @Test
    fun `no session with the adapter at the end of the window is an error, at the last failure`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = false, nowMs = 300_000L)
        t.startBegan(302_500L)
        t.sessionDown(332_500L) // the adapter search gave up
        t.startBegan(334_500L)
        t.sessionDown(364_500L)
        t.tick(300_000L + WINDOW_MS)
        val r = committed.single()
        assertEquals(Outcome.ENDED_EARLY, r.outcome)
        assertEquals(EndReason.ERROR, r.endReason)
        assertEquals(64_500L, r.endMs)
        assertEquals(2, r.starts)
    }

    @Test
    fun `a failure then a retry that shows a picture is a picture, from the retry's SUBSCRIBE`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.startBegan(300_010L)
        t.sessionDown(303_010L) // no reply to HELLO in 3 s
        t.startBegan(305_010L)
        t.subscribed(305_500L)
        t.picture(309_000L, 309_000L)
        t.tick(309_000L + SETTLE_MS)
        val r = committed.single()
        assertEquals(Outcome.PICTURE, r.outcome)
        assertEquals(2, r.starts)
        assertEquals(5_500L, r.subscribeMs)
        assertEquals(9_000L, r.pictureMs)
    }

    @Test
    fun `STREAMING and the adapter's lines just after the picture belong to it`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.startBegan(300_010L)
        t.subscribed(300_600L)
        t.picture(303_000L, 303_000L)
        t.adapterLines(listOf(box("[screen] carlink :9001 connected"), box(startupLine), box(timelineLine)), 303_800L)
        t.streaming(304_000L)
        t.tick(303_000L + SETTLE_MS)
        val r = committed.single()
        assertEquals(4_000L, r.streamingMs)
        assertEquals(AdapterStartup.parse(startupLine), r.adapter)
        assertEquals(1_802_400L, r.adapter?.uptimeMs)
        assertEquals(timelineLine, r.timeline)
    }

    @Test
    fun `the adapter's lines before the picture are kept, the latest of each`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.startBegan(300_010L)
        t.subscribed(300_600L)
        t.adapterLines(listOf(box(startupLine.replace("nudges 0+0", "nudges 1+0"))), 302_000L)
        t.adapterLines(listOf(box(startupLine)), 302_500L)
        t.picture(303_000L, 303_000L)
        t.adapterLines(listOf(box(startupLine.replace("nudges 0+0", "nudges 2+2"))), 303_500L)
        t.tick(303_000L + SETTLE_MS)
        assertEquals("the latest before the picture; none after it replaces it", 0, committed.single().adapter?.firstNudges)
    }

    @Test
    fun `a replayed adapter line is an earlier session's and is skipped`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.startBegan(300_010L)
        t.subscribed(300_600L)
        t.adapterLines(listOf(box(startupLine, flags = Ocbm.LOG_F_BACKFILL), box(timelineLine, flags = Ocbm.LOG_F_BACKFILL)), 300_700L)
        t.picture(303_000L, 303_000L)
        t.tick(303_000L + SETTLE_MS)
        assertNull(committed.single().adapter)
        assertNull(committed.single().timeline)
    }

    @Test
    fun `a timeline line not in the v1 form is not kept, and the log gets its length only`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.startBegan(300_010L)
        t.subscribed(300_600L)
        t.adapterLines(listOf(box("[timeline] v1 phone=Test iPhone")), 302_000L)
        t.picture(303_000L, 303_000L)
        t.tick(303_000L + SETTLE_MS)
        assertNull(committed.single().timeline)
        assertEquals(1, notes.size)
        assertTrue(notes.single().contains("31 characters"))
        assertFalse("never the line itself", notes.single().contains("Test iPhone"))
    }

    @Test
    fun `the session ending soon after the picture keeps the connect at once`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.startBegan(300_010L)
        t.subscribed(300_600L)
        t.picture(303_000L, 303_000L)
        t.sessionDown(304_000L)
        assertEquals(Outcome.PICTURE, committed.single().outcome)
        assertNull("STREAMING never came", committed.single().streamingMs)
    }

    @Test
    fun `a picture before SUBSCRIBE, or with no connect in flight, counts for nothing`() {
        val t = timer()
        t.picture(5_000L, 5_000L)
        t.startBegan(15_000L)
        t.picture(15_500L, 15_500L) // the last session's frame, before this one's SUBSCRIBE
        t.subscribed(16_000L)
        t.tick(11_100L + WINDOW_MS)
        assertEquals(Outcome.NO_PHONE, committed.single().outcome)
    }

    @Test
    fun `starts while the display is off begin no connect`() {
        val t = timer()
        t.displayOff(50_000L, 50_000L)
        t.startBegan(55_000L) // the adapter attached during the display-off wait
        t.subscribed(55_500L)
        t.tick(55_000L + WINDOW_MS + SETTLE_MS)
        assertTrue(committed.isEmpty())
    }

    @Test
    fun `a start between the display on and the wake's own becomes the wake's`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.startBegan(300_005L) // the attach broadcast's start won the race to the lock
        t.wake(300_000L, adapterListed = true, nowMs = 300_010L)
        t.subscribed(300_600L)
        t.picture(303_000L, 303_000L)
        t.closing(304_000L)
        val r = committed.single()
        assertEquals(Kind.WAKE_LISTED, r.kind)
        assertEquals(wall - 10L, r.startedAtMs)
        assertEquals(1, r.starts)
        assertEquals(3_000L, r.pictureMs)
    }

    @Test
    fun `the same wake reported twice is one connect`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.wake(300_000L, adapterListed = false, nowMs = 301_000L)
        t.closing(302_000L)
        assertEquals(Kind.WAKE_LISTED, committed.single().kind)
    }

    @Test
    fun `Carlink's screen coming to the front late shows in the record`() {
        val t = timer()
        t.appVisible(false, 100_000L)
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.startBegan(300_010L)
        t.subscribed(300_600L)
        t.appVisible(true, 341_500L) // the driver opened Carlink from another app
        t.picture(342_000L, 342_000L)
        t.tick(342_000L + SETTLE_MS)
        assertEquals(41_500L, committed.single().frontMs)
    }

    @Test
    fun `an app start's screen time is when the manager was built, from the process start`() {
        val t = timer(processStartMs = 11_100L, frontSince = 14_000L)
        t.startBegan(15_000L)
        t.appVisible(true, 15_500L) // an onStart after the manager was built: in front already, so no later
        t.subscribed(21_863L)
        t.picture(33_000L, 33_000L)
        t.closing(34_000L)
        assertEquals(2_900L, committed.single().frontMs)
    }

    @Test
    fun `a timer never told Carlink's screen is in front records none`() {
        val t = timer(frontSince = null)
        t.startBegan(15_000L)
        t.closing(16_000L)
        assertNull(committed.single().frontMs)
    }

    @Test
    fun `a connect that ended with Carlink's screen not in front says so`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.startBegan(300_010L)
        t.subscribed(300_600L)
        t.appVisible(false, 301_000L)
        t.tick(300_000L + WINDOW_MS)
        assertEquals(Outcome.NO_PHONE, committed.single().outcome)
        assertNull(committed.single().frontMs)
    }

    @Test
    fun `it asks to be ticked at the end of the window and of the picture's settle`() {
        val t = timer()
        t.offThenOn(onAt = 300_000L)
        t.wake(300_000L, adapterListed = true, nowMs = 300_000L)
        t.startBegan(300_010L)
        t.subscribed(300_600L)
        t.picture(303_000L, 303_000L)
        assertEquals(listOf(300_000L + WINDOW_MS, 303_000L + SETTLE_MS), scheduled)
    }
}
