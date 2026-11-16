package com.carlink

import com.carlink.DisplayOff.WakePath
import com.carlink.ocbm.OcbmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Carlink's starts, the display wake's own, and the undo of one a display OFF cut short: moved out of CarlinkManager, with
 * its session behind [Host], so their orderings can be tested (the Mac's 14 Nov review, W2).
 *
 * - [startIfDisconnected]: a start under the lifecycle [lock], only from DISCONNECTED. Cancelled before it sent SUBSCRIBE,
 *   it is undone (the stop runs on IO and can't be cancelled: W8); a session that came up is left alone (13 Nov, C1).
 * - [displayOn] / [displayOff] / [closing]: the wake (at once while the adapter is listed, with a short HELLO; else after
 *   the USB settle), a cut wake started again when the display is back, never after Close app or a handover.
 * - The HELLO wait is each start's own parameter: a start already under the lock never takes the wake's 3 s (W7).
 * - A start queued behind a running one, or after an undo, is kept in the wake's job, so another OFF, Close app or a
 *   handover cancels it, and it takes the wake's path once the lock is free, with its own label (W6).
 */
class WakeStarts(
    private val scope: CoroutineScope,
    private val lock: Mutex,
    private val host: Host,
    private val settleMs: Long = DisplayOff.WAKE_SETTLE_MS,
) {
    /** CarlinkManager's side. */
    @Suppress("TooManyFunctions") // one call each into the session; a holder would only rename them
    interface Host {
        /** The state's name, for the log lines. */
        fun stateName(): String

        /** DISCONNECTED: a start may begin. */
        fun disconnected(): Boolean

        /** CONNECTING. */
        fun connecting(): Boolean

        /**
         * The start in flight has sent SUBSCRIBE: read from the live client (OcbmClient.subscribed), so the undo's check
         * (the 13 Nov C1) needs no call that could be dropped from CarlinkManager.
         */
        fun subscribed(): Boolean

        fun released(): Boolean

        fun displayOn(): Boolean

        /** The adapter is still listed on USB: it kept its power while the display was off. */
        fun adapterListed(): Boolean

        fun cancelReconnect()

        /** The session's start (CarlinkManager.startImpl): open, HELLO within [helloMs], SUBSCRIBE. */
        suspend fun start(helloMs: Long)

        /** What a start cut short left behind (CarlinkManager.stopImpl). Blocking. */
        fun stop()

        fun log(line: String)

        /**
         * The connect ledger: a wake's connect begins, timed from the display on at [displayOnMs], with the adapter
         * listed on USB then or not ([DisplayOff.firstPath]). It only watches.
         */
        fun wakeConnecting(
            displayOnMs: Long,
            adapterListed: Boolean,
        ) {}
    }

    // A display OFF cut the wake in flight before SUBSCRIBE: the one cancel after which the undo starts again
    // ([restartAfterUndo]). Set only by [displayOff]; a second OFF while the cut start still runs must not clear it.
    // Cleared by the undo, [closing] and any start reaching SUBSCRIBE ([sessionUp]).
    @Volatile private var cut = false

    @Volatile private var job: Job? = null

    // A start of this class's is running ([startIfDisconnected], under the lock): host.subscribed() is about its client.
    @Volatile private var ownStart = false

    // That start reached SUBSCRIBE ([sessionUp]): still true after [ownStart] goes false in its finally, so a display OFF
    // between there and the wake job's end doesn't take the session that came up for a cut wake (W, the 2.6.0 review).
    // Cleared at each own start, and at a launch while no own start runs (R3-3).
    @Volatile private var ownStartUp = false

    // When the display came on, for the wake's line at STREAMING; 0 when no wake is being timed.
    @Volatile private var wakeAtMs = 0L

    @Volatile private var path = WakePath.AT_ONCE

    /** A start got no reply to HELLO within [helloMs]: the wake's short wait says so in its line. */
    fun helloMissed(helloMs: Long) {
        if (helloMs < OcbmClient.HELLO_TIMEOUT_MS) path = WakePath.HELLO_MISSED
    }

    /**
     * A start, only if nothing has started meanwhile: checked under the lifecycle lock, so two triggers for the same attach
     * can't tear down the session the first one just began.
     */
    suspend fun startIfDisconnected(
        reason: String,
        helloMs: Long = OcbmClient.HELLO_TIMEOUT_MS,
    ): Unit =
        lock.withLock {
            if (host.released() || !host.disconnected()) {
                host.log("[USB] $reason — state=${host.stateName()}, not starting")
                return@withLock
            }
            host.log("[USB] $reason — connecting")
            host.cancelReconnect()
            // From here the client is this start's own: subscribed() means its SUBSCRIBE, not an older session's being torn down.
            ownStart = true
            ownStartUp = false
            try {
                undoCancelledStart(
                    stuck = { startNeedsUndo(host.connecting(), host.subscribed()) },
                    undo = { undo() },
                ) { withContext(Dispatchers.IO) { host.start(helloMs) } }
            } finally {
                ownStart = false
            }
        }

    /**
     * A start reached SUBSCRIBE (CarlinkManager.startImpl): a wake's, a reconnect's or a Reset's. The session came up, and
     * a cut is no longer one to start again for.
     */
    fun sessionUp() {
        cut = false
        if (ownStart) ownStartUp = true
    }

    private suspend fun undo() {
        host.log("[LIFECYCLE] the start was cancelled before SUBSCRIBE: back to DISCONNECTED")
        // The caller is cancelled already. All of it NonCancellable, not just the stop: a withContext returning into the
        // cancelled caller throws there (its prompt-cancellation guarantee), which skipped the restart below.
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { host.stop() } // blocking USB work, off Main
            val again = restartAfterUndo(cut, host.displayOn(), host.released())
            cut = false
            if (again) launch(WakePath.AFTER_UNDO)
        }
    }

    /** The display went off: a wake in flight is cut, if it hasn't sent SUBSCRIBE (a session that came up is kept). */
    fun displayOff() {
        // Only this class's own start counts as subscribed: during the grace-end stop the old session's client still reads
        // subscribed until its CT_STOP, and a queued wake waiting behind that stop must still be cut (round 3 review).
        // Its start may have returned already, its job not yet ended: [ownStartUp] still says it came up (W).
        val cameUp = ownStartUp || (ownStart && host.subscribed())
        if (job?.isActive == true && !cameUp) cut = true
        job?.cancel()
        wakeAtMs = 0L
    }

    /**
     * The display came on at [nowMs] ([wasDormant]: the display-off wait had ended the session). From DISCONNECTED, the
     * wake; behind a cut wake, or the display-off teardown still running, a start queued behind it ([queueStartOnDisplayOn]).
     */
    fun displayOn(
        wasDormant: Boolean,
        nowMs: Long,
    ) {
        // The wake is timed only when a start is launched: an ON that starts nothing must not time the next STREAMING.
        if (host.disconnected()) {
            wakeAtMs = nowMs
            launch(null)
        } else if (queueStartOnDisplayOn(wakeCut = cut, wasDormant = wasDormant)) {
            wakeAtMs = nowMs
            launch(WakePath.QUEUED)
        }
    }

    /** Close app or a handover: no wake may start a session after it, nor arm [cut] again. */
    fun closing() {
        cut = false
        job?.cancel()
        wakeAtMs = 0L
    }

    /** STREAMING at [nowMs]: the wake's line, once, while a wake is being timed. */
    fun streaming(nowMs: Long): String? {
        val at = wakeAtMs
        wakeAtMs = 0L
        return if (at == 0L) null else DisplayOff.wakeLine(nowMs - at, path)
    }

    private fun launch(origin: WakePath?) {
        job?.cancel()
        // A cut own start may still be running on IO, its SUBSCRIBE about to land: its came-up mark stays (R3-3).
        if (!ownStart) ownStartUp = false
        job = scope.launch { connect(origin) }
    }

    /**
     * About 90% of the truck's connects are a wake with the adapter powered throughout (Scott, 13 Nov): connect at once
     * while it is still listed, and settle first only when it isn't, or when that open failed. A queued or after-undo
     * start ([origin]) first waits for what holds the lock.
     */
    private suspend fun connect(origin: WakePath?) {
        if (origin != null) lock.withLock { }
        val first = DisplayOff.firstPath(adapterListed = host.adapterListed())
        host.wakeConnecting(wakeAtMs, adapterListed = first == WakePath.AT_ONCE)
        path = origin ?: first
        val why =
            when (origin) {
                WakePath.QUEUED -> "Display on, after the start or stop in flight"
                WakePath.AFTER_UNDO -> "Display on, again after a display OFF undid its start"
                else -> "Display on"
            }
        if (first == WakePath.AT_ONCE) {
            startIfDisconnected("$why, at once (the adapter listed)", DisplayOff.WAKE_HELLO_MS)
            if (!host.disconnected()) return
            host.log("[POWER] the wake connect did not open the adapter: again after the USB settle")
            path = WakePath.OPEN_FAILED
        }
        delay(settleMs)
        startIfDisconnected("$why, after the USB settle")
    }
}
