package com.carlink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The adapter's USB attaches (USB_DEVICE_ATTACHED for a known adapter: MainActivity's receiver, through CarlinkManager),
 * counted, so a wait for "an attach after the ones I had seen" can't miss one that lands between its look and its wait.
 * Two waits use it: a wake whose display came on with the adapter not listed ([WakeStarts]), which connects as it
 * attaches, and the adapter search between its looks (CarlinkManager), which looks again at once. Thread-safe.
 */
class UsbAttaches {
    private val count = MutableStateFlow(0L)

    /** When the last attach came (elapsedRealtime), 0 before any: for the wake's log line. */
    @Volatile var lastAtMs = 0L
        private set

    /** The attaches so far: what a later [await] counts from. */
    val seen: Long get() = count.value

    /** The adapter attached, at [atMs] (elapsedRealtime). Any thread. */
    fun attached(atMs: Long) {
        lastAtMs = atMs
        count.update { it + 1 }
    }

    /** True as soon as there has been an attach after [since], at once if there has been one; false after [timeoutMs] with none. */
    suspend fun await(
        since: Long,
        timeoutMs: Long,
    ): Boolean {
        if (count.value > since) return true
        if (timeoutMs <= 0L) return false
        return withTimeoutOrNull(timeoutMs) { count.first { it > since } } != null
    }
}
