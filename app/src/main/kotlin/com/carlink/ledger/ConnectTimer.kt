package com.carlink.ledger

import com.carlink.DisplayOff
import com.carlink.ledger.ConnectRecord.EndReason
import com.carlink.ledger.ConnectRecord.Kind
import com.carlink.ledger.ConnectRecord.Origin
import com.carlink.ledger.ConnectRecord.Outcome
import com.carlink.ocbm.BoxLogEntry
import com.carlink.ocbm.Ocbm
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Times each connect for the connect ledger (M2), one [ConnectRecord] each. It only watches: CarlinkManager (and the
 * screen, for the picture) tells it what happened after it happened, and nothing here feeds back into connecting. Every
 * time is `SystemClock.elapsedRealtime()`, the head unit's uptime counting sleep. Thread-safe: events come from the main
 * thread, the start's IO thread and the adapter link's read thread.
 *
 * A connect begins at
 * - a wake ([wake]): the display came on and Carlink starts a session for it (WakeStarts), timed from the display on;
 * - the process's first start, within [WINDOW_MS] of the process start: an app start, timed from the process start;
 * - any other start while none is in flight ([startBegan]): a reconnect, timed from that start.
 *
 * A start while one is in flight (a retry, Reset connection) belongs to it; a start while the display is off begins none.
 * A wake that begins on a session already up (its start's SUBSCRIBE landed after a display OFF ended the record that
 * start was for) takes that session's SUBSCRIBE and STREAMING times.
 * It ends
 * - with a picture: the first video frame on screen within [WINDOW_MS] ([picture]); it stays open [SETTLE_MS] more for
 *   STREAMING and the adapter's lines, which come just after, unless the session ends first;
 * - early: the display off again ([displayOff]), Close app, a handover or the manager's release ([closing]);
 * - at [WINDOW_MS] with no picture: no phone while the session with the adapter is up, else ended early with an error, at
 *   its last failure ([sessionDown]).
 */
@Suppress("TooManyFunctions") // one entry point per event CarlinkManager reports, and the steps of a connect
class ConnectTimer(
    private val processStartMs: Long,
    private val commit: (ConnectRecord) -> Unit,
    /** Asked to call [tick] at about [atMs]; extra or early ticks are harmless. */
    private val schedule: (atMs: Long) -> Unit = {},
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Set once any connect began in this process: only the first may be an app start. */
    private val processBegun: AtomicBoolean = PROCESS_BEGUN,
    /** A line for the log, when an adapter's timeline line is turned away. */
    private val note: (String) -> Unit = {},
) {
    /** The connect in flight; times are elapsedRealtime. */
    private class Attempt(
        var kind: Kind,
        var originMs: Long,
        var startedAtMs: Long,
    ) {
        var starts = 0
        var subscribeAt: Long? = null
        var sessionUp = false
        var downAt: Long? = null
        var pictureAt: Long? = null
        var streamingAt: Long? = null
        var frontAt: Long? = null
        var suspendedMs: Long? = null
        var adapter: AdapterStartup? = null
        var timeline: String? = null
    }

    private var open: Attempt? = null
    private var displayOn = true

    // The last display OFF (elapsed and uptime), and the last ON with the head unit's sleep in between.
    private var offElapsedMs: Long? = null
    private var offUptimeMs = 0L
    private var onElapsedMs = 0L
    private var onSuspendedMs: Long? = null

    // Since when Carlink's screen has been in front ([appVisible]), null while it isn't or until it is told.
    private var frontSince: Long? = null

    // The session with the adapter, whether or not a connect is in flight: up since its SUBSCRIBE, and STREAMING since.
    // A display OFF and ON while a wake's start is still blocked in HELLO or SUBSCRIBE ends that wake's record before the
    // SUBSCRIBE lands; the wake that follows begins on the session already up and takes both ([adoptSession]).
    private var sessionUpSince: Long? = null
    private var streamingSince: Long? = null

    /** The display went off at [elapsedMs] ([uptimeMs]: SystemClock.uptimeMillis then). A connect in flight ends. */
    @Synchronized
    fun displayOff(
        elapsedMs: Long,
        uptimeMs: Long,
    ) {
        tick(elapsedMs)
        displayOn = false
        offElapsedMs = elapsedMs
        offUptimeMs = uptimeMs
        open?.let { a -> if (a.pictureAt != null) finish(a, Outcome.PICTURE) else finish(a, Outcome.ENDED_EARLY, EndReason.DISPLAY_OFF, elapsedMs) }
    }

    /** The display came on: the sleep since it went off, for the wake that may follow ([wake]). */
    @Synchronized
    fun displayOn(
        elapsedMs: Long,
        uptimeMs: Long,
    ) {
        tick(elapsedMs)
        displayOn = true
        onElapsedMs = elapsedMs
        onSuspendedMs = offElapsedMs?.let { off -> suspendedMs(elapsedMs - off, uptimeMs - offUptimeMs) }
    }

    /**
     * A wake's connect begins (WakeStarts): timed from [displayOnMs], the adapter listed on USB then or not. A start that
     * came between the display on and this becomes the wake's.
     */
    @Synchronized
    fun wake(
        displayOnMs: Long,
        adapterListed: Boolean,
        nowMs: Long,
    ) {
        tick(nowMs)
        if (displayOnMs <= 0L) return
        processBegun.set(true)
        val kind = if (adapterListed) Kind.WAKE_LISTED else Kind.WAKE_NOT_LISTED
        val suspended = onSuspendedMs.takeIf { displayOnMs == onElapsedMs }
        val a = open
        when {
            a != null && a.kind.origin == Origin.DISPLAY_ON && a.originMs == displayOnMs -> Unit // this wake's already
            a != null && a.pictureAt == null && a.originMs >= displayOnMs -> {
                a.kind = kind
                a.originMs = displayOnMs
                a.startedAtMs = wallClock() - (nowMs - displayOnMs)
                a.suspendedMs = suspended
                adoptSession(a)
                schedule(displayOnMs + WINDOW_MS)
            }
            else -> {
                a?.let { if (it.pictureAt != null) finish(it, Outcome.PICTURE) else finish(it, Outcome.ENDED_EARLY, EndReason.DISPLAY_OFF, displayOnMs) }
                val wake = begin(kind, displayOnMs, nowMs)
                wake.suspendedMs = suspended
                adoptSession(wake)
            }
        }
    }

    /**
     * A wake that begins while the session with the adapter is up takes it: its SUBSCRIBE (and STREAMING) came while no
     * connect was open, after a display OFF ended the one its start was for. A start never does: it tears the session
     * down first (CarlinkManager.startImpl), so [startBegan] forgets it.
     */
    private fun adoptSession(a: Attempt) {
        val up = sessionUpSince ?: return
        if (a.subscribeAt != null) return
        a.subscribeAt = up
        a.sessionUp = true
        if (a.streamingAt == null) a.streamingAt = streamingSince
    }

    /** Carlink begins a session (CarlinkManager.startImpl): a retry of the connect in flight, or a connect of its own. */
    @Synchronized
    fun startBegan(nowMs: Long) {
        tick(nowMs)
        // The start tears down any session that is up before it connects (CarlinkManager.startImpl): none carries over.
        forgetSession()
        if (!displayOn) return
        var a = open
        if (a?.pictureAt != null) {
            finish(a, Outcome.PICTURE)
            a = null
        }
        val attempt = a ?: beginStart(nowMs)
        attempt.starts++
        attempt.sessionUp = false
    }

    private fun beginStart(nowMs: Long): Attempt {
        val first = !processBegun.getAndSet(true)
        val appStart = first && processStartMs in 1..nowMs && nowMs - processStartMs < WINDOW_MS
        return if (appStart) begin(Kind.APP_START, processStartMs, nowMs) else begin(Kind.RECONNECT, nowMs, nowMs)
    }

    /** CT_SUBSCRIBE went out: the session with the adapter is up, kept even with no connect open ([adoptSession]). */
    @Synchronized
    fun subscribed(nowMs: Long) {
        tick(nowMs)
        sessionUpSince = nowMs
        streamingSince = null
        val a = open ?: return
        if (a.pictureAt != null) return
        a.subscribeAt = nowMs
        a.sessionUp = true
    }

    /** The session with the adapter failed or was torn down without the connect ending ([CarlinkManager]'s error paths). */
    @Synchronized
    fun sessionDown(nowMs: Long) {
        tick(nowMs)
        forgetSession()
        val a = open ?: return
        if (a.pictureAt != null) {
            finish(a, Outcome.PICTURE)
            return
        }
        a.sessionUp = false
        a.downAt = nowMs
    }

    /** STREAMING: the phone's first media once keyed, or its key when the media came first. */
    @Synchronized
    fun streaming(nowMs: Long) {
        tick(nowMs)
        if (sessionUpSince != null && streamingSince == null) streamingSince = nowMs
        val a = open ?: return
        val subscribe = a.subscribeAt ?: return
        if (a.streamingAt == null && nowMs >= subscribe) a.streamingAt = nowMs
    }

    /**
     * The first video frame on screen at [atMs] (MainScreen: the loading screen lifting over a frame the decoder drew, or
     * the frame drawn after it lifted). Counts for the connect in flight once it has sent SUBSCRIBE, within [WINDOW_MS].
     */
    @Synchronized
    fun picture(
        atMs: Long,
        nowMs: Long,
    ) {
        val a = open
        if (a != null && a.pictureAt == null && counts(a, atMs)) {
            a.pictureAt = atMs
            a.frontAt = frontSince
            schedule(atMs + SETTLE_MS)
        }
        tick(nowMs)
    }

    /** A picture at [atMs] is [a]'s: after its SUBSCRIBE, within its window. */
    private fun counts(
        a: Attempt,
        atMs: Long,
    ): Boolean {
        val subscribe = a.subscribeAt ?: return false
        return atMs >= subscribe && atMs - a.originMs < WINDOW_MS
    }

    /** Close app, a handover of the adapter, or the manager released: a connect in flight ends. */
    @Synchronized
    fun closing(nowMs: Long) {
        tick(nowMs)
        forgetSession()
        val a = open ?: return
        if (a.pictureAt != null) finish(a, Outcome.PICTURE) else finish(a, Outcome.ENDED_EARLY, EndReason.APP_CLOSED, nowMs)
    }

    /** Carlink's screen came to the front ([visible]) or left it (MainActivity's onStart and onStop). */
    @Synchronized
    fun appVisible(
        visible: Boolean,
        nowMs: Long,
    ) {
        tick(nowMs)
        frontSince = if (visible) frontSince ?: nowMs else null
    }

    /**
     * Box log lines, as the adapter's log stream brings them (only while it is armed: drive logs or a lane request). Its
     * `[startup]` line and its `[timeline]` line go to the connect in flight; a replayed (backfilled) line is an earlier
     * session's and is skipped. After the picture, only a line not yet seen is taken.
     */
    @Synchronized
    fun adapterLines(
        entries: List<BoxLogEntry>,
        nowMs: Long,
    ) {
        tick(nowMs)
        val a = open ?: return
        for (e in entries) {
            if (e.flags and Ocbm.LOG_F_BACKFILL != 0) continue
            val text = e.text
            if (text.startsWith(AdapterStartup.PREFIX)) {
                val startup = AdapterStartup.parse(text)
                if (startup != null && (a.pictureAt == null || a.adapter == null)) a.adapter = startup
            } else if (text.startsWith(AdapterTimeline.PREFIX)) {
                val kept = AdapterTimeline.accept(text)
                if (kept == null) {
                    note("[LEDGER] the adapter's timeline line (${text.length} characters) is not the v1 form: not kept")
                } else if (a.pictureAt == null || a.timeline == null) {
                    a.timeline = kept
                }
            }
        }
    }

    /** Ends what is due by [nowMs]: a picture's settle, or the window of a connect with none. */
    @Synchronized
    fun tick(nowMs: Long) {
        val a = open ?: return
        val picture = a.pictureAt
        when {
            picture != null -> if (nowMs >= picture + SETTLE_MS) finish(a, Outcome.PICTURE)
            nowMs - a.originMs < WINDOW_MS -> Unit
            a.sessionUp -> finish(a, Outcome.NO_PHONE, endAtMs = a.originMs + WINDOW_MS)
            else -> finish(a, Outcome.ENDED_EARLY, EndReason.ERROR, a.downAt ?: (a.originMs + WINDOW_MS))
        }
    }

    private fun forgetSession() {
        sessionUpSince = null
        streamingSince = null
    }

    private fun begin(
        kind: Kind,
        originMs: Long,
        nowMs: Long,
    ): Attempt {
        val a = Attempt(kind, originMs, wallClock() - (nowMs - originMs))
        open = a
        schedule(originMs + WINDOW_MS)
        return a
    }

    private fun finish(
        a: Attempt,
        outcome: Outcome,
        reason: EndReason? = null,
        endAtMs: Long = a.pictureAt ?: a.originMs,
    ) {
        open = null
        val origin = a.originMs

        fun from(t: Long?): Long? = t?.let { (it - origin).coerceAtLeast(0) }
        val front = if (outcome == Outcome.PICTURE) a.frontAt else frontSince
        commit(
            ConnectRecord(
                startedAtMs = a.startedAtMs,
                kind = a.kind,
                outcome = outcome,
                endMs = endAtMs - origin,
                endReason = reason,
                subscribeMs = from(a.subscribeAt),
                pictureMs = if (outcome == Outcome.PICTURE) from(a.pictureAt) else null,
                streamingMs = from(a.streamingAt),
                starts = a.starts,
                frontMs = from(front),
                suspendedMs = a.suspendedMs,
                adapter = a.adapter,
                timeline = a.timeline,
            ),
        )
    }

    companion object {
        /** No picture by then is no phone: the wake line's own limit ([DisplayOff.WAKE_TIMED_MAX_MS]). */
        const val WINDOW_MS = DisplayOff.WAKE_TIMED_MAX_MS

        /** After the picture, how long STREAMING and the adapter's lines may still come for it. */
        const val SETTLE_MS = 10_000L

        /** Less sleep than this between the display going off and coming on is none: the clocks are read one after the other. */
        const val SUSPEND_MIN_MS = 100L

        /** This process's: any connect begun ([processBegun]). */
        val PROCESS_BEGUN = AtomicBoolean(false)

        /** The head unit's sleep in a span that took [elapsedMs] of elapsedRealtime and [uptimeMs] of uptimeMillis. */
        fun suspendedMs(
            elapsedMs: Long,
            uptimeMs: Long,
        ): Long = (elapsedMs - uptimeMs).let { if (it < SUSPEND_MIN_MS) 0L else it }
    }
}
