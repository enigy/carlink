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
 * When Carlink last sent CT_STOP to a subscribed adapter (OcbmClient.stop), on the monotonic clock: the box tears that
 * session down for about [CleanSlateReset.BOX_TEARDOWN_SETTLE_MS] after it. Process-wide: a new manager meets the same box.
 */
object BoxTeardown {
    @Volatile var stoppedAtMs = 0L

    fun nowMs(): Long = System.nanoTime() / NANOS_PER_MS

    private const val NANOS_PER_MS = 1_000_000L
}

/**
 * How long a start about to send HELLO at [nowMs] waits for the box to finish tearing down the session stopped at
 * [stoppedAtMs] (0: none): what is left of [settleMs] from that CT_STOP. A display ON within seconds of Carlink's own stop
 * (the grace end, or a wake) re-subscribed into the teardown, and on any OCBM before 251b590 the box then lost wireless
 * until a reset (the Mac's 14 Nov review, W1). Measured as the start reaches HELLO, not when it was asked for, so an ON
 * that lands while the stop still runs counts from that stop's CT_STOP.
 */
fun holdBeforeHello(
    nowMs: Long,
    stoppedAtMs: Long,
    settleMs: Long,
): Long = if (stoppedAtMs == 0L) 0L else (settleMs - (nowMs - stoppedAtMs)).coerceIn(0L, settleMs)
