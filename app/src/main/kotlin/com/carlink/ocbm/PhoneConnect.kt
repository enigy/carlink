package com.carlink.ocbm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One Connect press in Settings › Phones, from the tap to its [outcome] (null while it works). */
data class ConnectAttempt(
    /** The phone asked for, lower-case. */
    val mac: String,
    val outcome: Outcome? = null,
) {
    enum class Outcome { CONNECTED, NOT_REACHED }

    val working: Boolean get() = outcome == null
}

/** The phone the adapter says is live: its address (CT_PHONE_IDENT), and how many identities have arrived. */
data class LivePhone(
    val mac: String?,
    val identities: Long,
)

/**
 * The phone on the adapter, as Carlink keeps it: [identified] by its own CT_PHONE_IDENT, and [shown], the one Settings
 * marks Connected, which MGMT_INFO's one-bonded-phone guess also fills for an adapter that names no phone. The adapter
 * clearing the identity, or saying no phone is on it, empties both (11-03 bench: through a switch the old phone's row
 * read "Connected" until the new phone named itself, because only a stop or an error cleared it).
 */
class AdapterPhone {
    /** The phone's `deviceID` (its Bluetooth address), lower-case, or null. */
    @Volatile var identified: String? = null
        private set

    @Volatile var shown: String? = null
        private set

    /** How many identities have arrived: a Connect counts as reached only through news ([PhoneConnectTracker]). */
    @Volatile var identities = 0L
        private set

    val live: LivePhone get() = LivePhone(identified, identities)

    /** CT_PHONE_IDENT naming [mac], or null when the adapter cleared it: that phone's session is over. */
    fun identity(mac: String?) {
        identified = mac
        shown = mac
        if (mac != null) identities++
    }

    /** SEV_PHONE_ABSENT: no phone on the adapter, whatever the last identity said. */
    fun absent() {
        identified = null
        shown = null
    }

    /**
     * MGMT_INFO, which names no phone: the identified one, else with a phone on the adapter ([phoneOn]) and just one
     * [bonded], that one. With two or more bonded and no identity, which is live stays unknown.
     */
    fun info(
        phoneOn: Boolean,
        bonded: Set<String>,
    ) {
        identified?.let { shown = it }
        if (shown == null && phoneOn) bonded.singleOrNull()?.let { shown = it }
    }

    /** A stop, a restart or an error: no phone is marked Connected until the adapter says again. */
    fun clearShown() {
        shown = null
    }
}

/**
 * What Settings › Phones' Connect shows after a press (owner, 11-01 bench: nothing on screen said the press
 * registered, so the driver pressed again, then tried Forget). [start] shows the press at once, and further
 * presses are ignored while it works. It ends [ConnectAttempt.Outcome.CONNECTED] when the adapter names the
 * phone asked for as live, [ConnectAttempt.Outcome.NOT_REACHED] when it doesn't within [Timing.waitMs] (the
 * adapter's 45 s switch hold) or turns the request down ([refused]). The outcome then stands for
 * [Timing.outcomeMs].
 *
 * The phone counts as reached only through news after the press: a new identity for it, or it becoming the
 * live phone. A fresh Carlink is replayed the last session's identity before any phone connects (11-01), and
 * that stale name alone must not read as "Connected".
 */
class PhoneConnectTracker(
    private val scope: CoroutineScope,
    private val live: () -> LivePhone,
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val waitMs: Long = 45_000,
        val pollMs: Long = 250,
        val outcomeMs: Long = 8_000,
    )

    private val state = MutableStateFlow<ConnectAttempt?>(null)

    /** The press being followed, or its outcome while it stands; null otherwise. */
    val attempt: StateFlow<ConnectAttempt?> = state.asStateFlow()

    private val lock = Any()
    private var follow: Job? = null
    private var clear: Job? = null

    /** A press on [mac]'s Connect: false, and nothing changes, while an earlier press still works. */
    fun start(mac: String): Boolean {
        synchronized(lock) {
            if (state.value?.working == true) return false
            val asked = ConnectAttempt(mac.lowercase())
            val before = live()
            state.value = asked
            clear?.cancel()
            follow = scope.launch { follow(asked, before) }
        }
        return true
    }

    /** The adapter turned the request for [mac] down, or didn't answer: not reached, at once. */
    fun refused(mac: String) {
        if (finish(mac.lowercase(), ConnectAttempt.Outcome.NOT_REACHED)) follow?.cancel()
    }

    private suspend fun follow(
        asked: ConnectAttempt,
        before: LivePhone,
    ) {
        var waited = 0L
        while (waited < timing.waitMs) {
            val now = live()
            val named = now.mac.equals(asked.mac, ignoreCase = true)
            if (named && (!before.mac.equals(asked.mac, ignoreCase = true) || now.identities > before.identities)) {
                finish(asked.mac, ConnectAttempt.Outcome.CONNECTED)
                return
            }
            delay(timing.pollMs)
            waited += timing.pollMs
        }
        finish(asked.mac, ConnectAttempt.Outcome.NOT_REACHED)
    }

    /** [mac]'s press ends in [outcome], which stands for [Timing.outcomeMs]; false when it wasn't working. */
    private fun finish(
        mac: String,
        outcome: ConnectAttempt.Outcome,
    ): Boolean {
        synchronized(lock) {
            val now = state.value
            if (now == null || now.mac != mac || !now.working) return false
            val done = now.copy(outcome = outcome)
            state.value = done
            clear?.cancel()
            clear =
                scope.launch {
                    delay(timing.outcomeMs)
                    synchronized(lock) { if (state.value === done) state.value = null }
                }
        }
        return true
    }
}
