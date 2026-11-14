package com.carlink

import kotlinx.coroutines.CancellationException

/**
 * A start cancelled mid-way (a display OFF in the first moments of a wake's start cancels it) left CONNECTING with no
 * session, and a display ON then did nothing until Reset Device (the Mac's 13 Nov review, CL-WAKE-3). Runs [start];
 * if it is cancelled while [stuck] holds, [undo] puts the manager back to DISCONNECTED, and the cancellation goes on.
 */
suspend fun <T> undoCancelledStart(
    stuck: () -> Boolean,
    undo: suspend () -> Unit,
    start: suspend () -> T,
): T =
    try {
        start()
    } catch (e: CancellationException) {
        if (stuck()) undo()
        throw e
    }

/**
 * Whether a cancelled start left nothing behind but CONNECTING: it never sent SUBSCRIBE. CONNECTING alone also holds
 * after a start that finished (SUBSCRIBE sent, waiting for the phone), and undoing that ended a session that had come
 * up (the Mac's 13 Nov review, C1).
 */
fun startNeedsUndo(
    connecting: Boolean,
    subscribed: Boolean,
): Boolean = connecting && !subscribed

/**
 * Whether an undone start starts again: only when a display OFF cut the wake's own start ([wakeCut]) and the display is
 * on again by now, never after Close app or a handover (which also cancel a start), and never on a released manager.
 */
fun restartAfterUndo(
    wakeCut: Boolean,
    displayOn: Boolean,
    released: Boolean,
): Boolean = wakeCut && displayOn && !released

/**
 * Whether a display ON that finds Carlink not DISCONNECTED queues a start behind what is running: only when a display OFF
 * cut the wake ([wakeCut]) or the display-off teardown is still ending the session ([wasDormant]). The queued start runs
 * only from DISCONNECTED, so a session that came up is left alone (the 13 Nov lifecycle review).
 */
fun queueStartOnDisplayOn(
    wakeCut: Boolean,
    wasDormant: Boolean,
): Boolean = wakeCut || wasDormant

/**
 * When Carlink last stopped a subscribed adapter (CarlinkManager.stopImpl, which sends CT_STOP), on [clock]: the box tears
 * that session down for about [settleMs] after it. On the head unit [clock] is SystemClock.elapsedRealtime, which keeps
 * counting while it sleeps: System.nanoTime stops, so a head unit suspended soon after the grace-end stop held the next
 * morning's wake for up to 5 s (the Mac's 14 Nov review). One per process ([process]): a new manager meets the same box.
 */
class BoxTeardown(
    private val clock: () -> Long,
    private val settleMs: Long = CleanSlateReset.BOX_TEARDOWN_SETTLE_MS,
) {
    @Volatile private var stoppedAtMs = 0L

    /** A subscribed client was just stopped: CT_STOP went to the box. */
    fun stopped() {
        stoppedAtMs = clock()
    }

    /** How long a start about to send HELLO waits now ([holdBeforeHello]). */
    fun holdMs(): Long = holdBeforeHello(clock(), stoppedAtMs, settleMs)

    companion object {
        /** The head unit's. */
        val process: BoxTeardown by lazy { BoxTeardown({ android.os.SystemClock.elapsedRealtime() }) }
    }
}

/**
 * How long a start about to send HELLO at [nowMs] waits for the box to finish tearing down the session stopped at
 * [stoppedAtMs] (0: none): what is left of [settleMs] from that CT_STOP. Any connect within seconds of Carlink's own stop
 * (the grace end, a wake, a reopen) re-subscribed into the box's teardown, which OCBM before 251b590 doesn't wait for
 * (the Mac's 14 Nov review, W1). Measured as the start reaches HELLO, not when it was asked for, so an ON
 * that lands while the stop still runs counts from that stop's CT_STOP.
 */
fun holdBeforeHello(
    nowMs: Long,
    stoppedAtMs: Long,
    settleMs: Long,
): Long = if (stoppedAtMs == 0L) 0L else (settleMs - (nowMs - stoppedAtMs)).coerceIn(0L, settleMs)
