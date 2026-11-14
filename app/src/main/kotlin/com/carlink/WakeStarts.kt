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
    interface Host {
        /** The state's name, for the log lines. */
        fun stateName(): String

        /** DISCONNECTED: a start may begin. */
        fun disconnected(): Boolean

        /** CONNECTING. */
        fun connecting(): Boolean

        fun released(): Boolean

        fun displayOn(): Boolean

        /** The adapter is still listed on USB: it kept its power while the display was off. */
        fun adapterListed(): Boolean

        fun cancelReconnect()

        /** The session's start (CarlinkManager.startImpl): open, HELLO within [helloMs], SUBSCRIBE ([subscribed]). */
        suspend fun start(helloMs: Long)

        /** What a start cut short left behind (CarlinkManager.stopImpl). Blocking. */
        fun stop()

        fun log(line: String)
    }

    // This start sent SUBSCRIBE: a cancel after it leaves a session that came up ([startNeedsUndo]).
    @Volatile private var subscribedThisStart = false

    // A display OFF cut the wake in flight: the one cancel after which the undo starts again ([restartAfterUndo]). Set only
    // by [displayOff]; a second OFF while the cut start still runs must not clear it (13 Nov lifecycle review).
    @Volatile private var cut = false

    @Volatile private var job: Job? = null

    // When the display came on, for the wake's line at STREAMING; 0 when no wake is being timed.
    @Volatile private var wakeAtMs = 0L

    @Volatile private var path = WakePath.AT_ONCE

    /** The start in flight reached SUBSCRIBE (CarlinkManager.startImpl). */
    fun subscribed() {
        subscribedThisStart = true
        cut = false
    }

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
            subscribedThisStart = false
            undoCancelledStart(
                stuck = { startNeedsUndo(host.connecting(), subscribedThisStart) },
                undo = { undo() },
            ) { withContext(Dispatchers.IO) { host.start(helloMs) } }
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

    /** The display went off: a wake in flight is cut. */
    fun displayOff() {
        if (job?.isActive == true) cut = true
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
        wakeAtMs = nowMs
        if (host.disconnected()) {
            launch(null)
        } else if (queueStartOnDisplayOn(wakeCut = cut, wasDormant = wasDormant)) {
            launch(WakePath.QUEUED)
        }
    }

    /** Close app or a handover: no wake may start a session after it, nor arm [cut] again. */
    fun closing() {
        cut = false
        job?.cancel()
    }

    /** STREAMING at [nowMs]: the wake's line, once, while a wake is being timed. */
    fun streaming(nowMs: Long): String? {
        val at = wakeAtMs
        wakeAtMs = 0L
        return if (at == 0L) null else DisplayOff.wakeLine(nowMs - at, path)
    }

    private fun launch(origin: WakePath?) {
        job?.cancel()
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
        path = origin ?: first
        if (first == WakePath.AT_ONCE) {
            startIfDisconnected("Display on, the adapter still listed", DisplayOff.WAKE_HELLO_MS)
            if (!host.disconnected()) return
            host.log("[POWER] the wake connect did not open the adapter: again after the USB settle")
            path = WakePath.OPEN_FAILED
        }
        delay(settleMs)
        startIfDisconnected("Display on")
    }
}
