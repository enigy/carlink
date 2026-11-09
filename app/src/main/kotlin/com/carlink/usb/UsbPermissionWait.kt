package com.carlink.usb

import android.os.SystemClock
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.enigy.carlink2.bridge.BridgeContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The bridge's USB permission prompt for the adapter, from the first ask until it is answered or given up on, and the
 * one owner of [UsbPermissionPrompt] for all of it.
 *
 * [await] asks (once per attach; the bridge shows the one-tap prompt) and waits up to [Timing.promptWaitMs]. A prompt
 * still unanswered then is [Outcome.PENDING], and the flag STAYS SET: the caller hands it to [watch], which keeps it set
 * while it waits up to [Timing.watchMs] more and runs its `onGrant` on a late OK (8 Nov bench: an OK tapped after the
 * 30 s wait was never picked up), or [abandon]s it. Before this the flag went false between the two, and MainActivity
 * releases its held cluster steps on any false: CarAppActivity could launch over the open prompt, which leaves the
 * screen ignoring touches ([UsbPermissionPrompt]).
 *
 * A watch is never restarted under itself: [watch] for the device already watched keeps the running one, and only the
 * current watch may clear the flag, so a replaced watch's end can't clear it under its successor.
 */
class UsbPermissionWait(
    private val scope: CoroutineScope,
    private val timing: Timing = Timing(),
    private val setPrompt: (Boolean) -> Unit = UsbPermissionPrompt::set,
) {
    /** The bridge, for one device. */
    interface Port {
        fun hasPermission(): Boolean

        /** A `BridgeContract.PERMISSION_*`; the first call shows the prompt, later ones report it. */
        fun requestPermission(): Int
    }

    data class Timing(
        /** Polls for the platform's own grant before prompting (the attach broadcast can come a moment early). */
        val gracePolls: Int = 8,
        val pollMs: Long = 250,
        /** How long a prompt may stay unanswered before [await] hands it over as [Outcome.PENDING]. */
        val promptWaitMs: Long = 30_000,
        /** How long [watch] keeps waiting for a late answer. */
        val watchMs: Long = 10 * 60_000L,
        val watchPollMs: Long = 1_000,
        val now: () -> Long = { SystemClock.elapsedRealtime() },
    )

    enum class Outcome { GRANTED, CLOSED, PENDING }

    private val lock = Any()
    private var job: Job? = null
    private var watched: String? = null
    private var generation = 0

    /** A late answer is being watched for. */
    val watching: Boolean get() = synchronized(lock) { job?.isActive == true }

    /**
     * Wait for permission for [name]: [Outcome.GRANTED], [Outcome.CLOSED] (denied, or the adapter left) or
     * [Outcome.PENDING], which leaves the flag set for [watch] or [abandon].
     */
    suspend fun await(
        port: Port,
        name: String,
        onPrompt: () -> Unit = {},
    ): Outcome {
        repeat(timing.gracePolls) {
            if (port.hasPermission()) return Outcome.GRANTED
            delay(timing.pollMs)
        }
        if (port.hasPermission()) return Outcome.GRANTED
        val prompt = runCatching { port.requestPermission() }.getOrDefault(BridgeContract.PERMISSION_DENIED)
        logInfo("[BRIDGE] no USB permission for $name — prompt result $prompt; waiting for an answer", tag = Logger.Tags.USB)
        setPrompt(true)
        onPrompt()
        val deadline = timing.now() + timing.promptWaitMs
        var outcome = Outcome.PENDING
        while (outcome == Outcome.PENDING && timing.now() < deadline) {
            outcome = answer(port)
            if (outcome == Outcome.PENDING) delay(timing.pollMs)
        }
        if (outcome == Outcome.PENDING) outcome = answer(port)
        if (outcome != Outcome.PENDING) clearUnlessWatching()
        return outcome
    }

    /**
     * Keep waiting for [name]'s pending prompt, with the flag set, and run [onGrant] on a late OK. Ends on an answer,
     * after [Timing.watchMs], or when [stillWanted] turns false. A watch for [name] already running is kept.
     */
    fun watch(
        port: Port,
        name: String,
        stillWanted: () -> Boolean,
        onGrant: suspend () -> Unit,
    ) {
        val gen =
            synchronized(lock) {
                if (job?.isActive == true && watched == name) return
                job?.cancel()
                watched = name
                ++generation
            }
        setPrompt(true)
        val started =
            scope.launch(Dispatchers.IO) {
                logInfo("[BRIDGE] the USB prompt for $name is still unanswered: listening for a late OK", tag = Logger.Tags.USB)
                var outcome = Outcome.PENDING
                try {
                    val until = timing.now() + timing.watchMs
                    while (outcome == Outcome.PENDING && timing.now() < until && stillWanted()) {
                        delay(timing.watchPollMs)
                        outcome = answer(port)
                    }
                } finally {
                    ended(gen)
                }
                when (outcome) {
                    Outcome.GRANTED -> {
                        logInfo("[BRIDGE] USB permission for $name granted after the wait: connecting", tag = Logger.Tags.USB)
                        onGrant()
                    }
                    Outcome.CLOSED -> logWarn("[BRIDGE] the USB prompt for $name was denied (or the adapter left)", tag = Logger.Tags.USB)
                    Outcome.PENDING -> logWarn("[BRIDGE] no answer to the USB prompt for $name: stopped listening", tag = Logger.Tags.USB)
                }
            }
        synchronized(lock) { if (generation == gen) job = started }
    }

    /** The caller won't watch a [Outcome.PENDING] prompt: let the flag go. */
    fun abandon() = clearUnlessWatching()

    private fun answer(port: Port): Outcome {
        if (runCatching { port.hasPermission() }.getOrDefault(false)) return Outcome.GRANTED
        val now = runCatching { port.requestPermission() }.getOrDefault(BridgeContract.PERMISSION_DENIED)
        return if (now == BridgeContract.PERMISSION_DENIED || now == BridgeContract.PERMISSION_NO_DEVICE) Outcome.CLOSED else Outcome.PENDING
    }

    private fun clearUnlessWatching() {
        if (!watching) setPrompt(false)
    }

    /** Only the current watch lets the flag go: a replaced one's end must not clear it under its successor. */
    private fun ended(gen: Int) {
        val current = synchronized(lock) { (generation == gen).also { if (it) watched = null } }
        if (current) setPrompt(false)
    }
}
