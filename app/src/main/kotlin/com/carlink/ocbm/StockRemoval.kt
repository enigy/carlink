package com.carlink.ocbm

import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where Settings › Adapter's "Remove stock CarPlay" stands ([StockRemover.state]). */
sealed interface StockRemoval {
    data object Idle : StockRemoval

    /** Checking the trial, or sending the install confirm. */
    data object Working : StockRemoval

    /** The trial has proven itself: the driver is asked. */
    data object Asking : StockRemoval

    /** It can't be removed now ([reason]); nothing was sent. */
    data class NotNow(
        val reason: String,
    ) : StockRemoval

    /** The adapter didn't take the install confirm ([reason]). */
    data class Failed(
        val reason: String,
    ) : StockRemoval
}

/**
 * "Remove stock CarPlay" in Settings › Adapter (Scott, 2 Nov): the driver finishes a proven trial on purpose, so
 * later updates have the room without asking. It checks the trial as an update needing the room does ([proven]: a
 * passed self-test and a CarPlay session; [finishBlocked]), asks, and on Yes sends the install confirm ([confirm]).
 * The adapter removes stock CarPlay when that drive ends; the install then reads `confirmed`, then `final`.
 */
class StockRemover(
    /** Why the trial can't finish now, or null when it may. Blocks: reads the adapter's self-test. */
    private val proven: () -> String?,
    /** MGMT_INSTALL_CONFIRM: the ACK's status, or null with no answer. Blocks. */
    private val confirm: () -> Int?,
    private val scope: CoroutineScope,
) {
    private val stateFlow = MutableStateFlow<StockRemoval>(StockRemoval.Idle)

    val state: StateFlow<StockRemoval> = stateFlow.asStateFlow()

    private val lock = Any()

    /** The driver tapped Remove: check the trial, then ask ([StockRemoval.Asking]) or say why not. */
    fun ask() {
        synchronized(lock) {
            if (stateFlow.value != StockRemoval.Idle) return
            stateFlow.value = StockRemoval.Working
        }
        logInfo("[UI_ACTION] Adapter: Remove stock CarPlay", tag = "UI")
        scope.launch(Dispatchers.IO) {
            val why = proven()
            if (why != null) logInfo("[INSTALL] Remove stock CarPlay: not now: $why", tag = Logger.Tags.ADAPTR)
            settle(StockRemoval.Working, if (why == null) StockRemoval.Asking else StockRemoval.NotNow(why))
        }
    }

    /** The driver said Yes: send the install confirm. */
    fun remove() {
        synchronized(lock) {
            if (stateFlow.value != StockRemoval.Asking) return
            stateFlow.value = StockRemoval.Working
        }
        logWarn("[INSTALL] Remove stock CarPlay agreed in the app", tag = Logger.Tags.ADAPTR)
        scope.launch(Dispatchers.IO) {
            val next =
                when (confirm()) {
                    0 -> StockRemoval.Idle
                    null -> StockRemoval.Failed("The adapter didn't answer. Nothing was changed.")
                    else -> StockRemoval.Failed("The adapter refused it. Nothing was changed.")
                }
            settle(StockRemoval.Working, next)
        }
    }

    /** No on the question, OK on an answer, or the session ended: back to Idle. */
    fun dismiss() {
        val was: StockRemoval
        synchronized(lock) {
            was = stateFlow.value
            if (was != StockRemoval.Working) stateFlow.value = StockRemoval.Idle
        }
        // Bench 11-03: the log said "agreed" and "not now", but nothing when the driver asked and backed out.
        if (was == StockRemoval.Asking) logInfo("[UI_ACTION] Adapter: Remove stock CarPlay cancelled", tag = "UI")
    }

    /** The session ended: whatever was asked or answered was about that adapter. */
    fun reset() {
        synchronized(lock) { stateFlow.value = StockRemoval.Idle }
    }

    private fun settle(
        from: StockRemoval,
        to: StockRemoval,
    ) {
        synchronized(lock) { if (stateFlow.value == from) stateFlow.value = to }
    }
}
