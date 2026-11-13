package com.carlink

/**
 * The adapter's boot radio preload state (OCBM MGMT_INFO "preload": on, off_guard, off_owner; absent before
 * connect-speed), as one log line per session, and again within one only if it changes. MGMT_INFO comes about once a
 * second while Settings is open.
 */
class PreloadNote {
    private var last = ""

    /** A new session: its first report is logged. */
    @Synchronized
    fun newSession() {
        last = ""
    }

    /** The line for `state`, or null when it says nothing new. */
    @Synchronized
    fun line(state: String): String? {
        if (state.isEmpty() || state == last) return null
        last = state
        val words = if (state == "off_guard") ": its guard switched it off after unfinished preloads" else ""
        return "[ADAPTER] boot radio preload $state$words"
    }
}
