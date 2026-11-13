package com.carlink

/**
 * Carlink's own start, for a truck Save Logs taken after a cold start (the Mac's 13 Nov brief, item 5): the head unit's
 * uptime at the process start, then the first time the adapter is seen on USB, the bridge open and the first SUBSCRIBE,
 * each from the process start. With the adapter's `[startup]` line it places the head unit's own boot. All times are
 * `SystemClock.elapsedRealtime()`, the head unit's uptime. One line per process, at its first SUBSCRIBE.
 */
class ColdStart(
    private val processStartMs: Long,
) {
    private var adapterSeenMs = 0L
    private var bridgeOpenMs = 0L
    private var logged = false

    /** The adapter is on USB (listed, or its attach seen); the first time counts. */
    fun adapterSeen(nowMs: Long) {
        if (adapterSeenMs == 0L) adapterSeenMs = nowMs
    }

    /** The bridge opened the adapter; the first time counts. */
    fun bridgeOpen(nowMs: Long) {
        if (bridgeOpenMs == 0L) bridgeOpenMs = nowMs
    }

    /** The first SUBSCRIBE went out: the line, once per process; null after. */
    @Synchronized
    fun subscribed(nowMs: Long): String? {
        if (logged) return null
        logged = true

        fun at(ms: Long) = if (ms == 0L) "-" else "+${ms - processStartMs} ms"
        return "[startup] carlink: head unit up ${processStartMs / 1000}.${processStartMs % 1000 / 100} s at the process " +
            "start; adapter seen ${at(adapterSeenMs)}, bridge open ${at(bridgeOpenMs)}, SUBSCRIBE ${at(nowMs)}"
    }

    companion object {
        /** This process's: one per process, whatever CarlinkManager instances come and go. */
        val process: ColdStart by lazy { ColdStart(android.os.Process.getStartElapsedRealtime()) }
    }
}
