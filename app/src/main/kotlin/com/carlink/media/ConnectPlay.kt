package com.carlink.media

/**
 * Whether to press Play for the phone when its music status first arrives in a session.
 *
 * Two reasons to. The car asked: Android Automotive's `CarMediaService` sends this app `play()` at
 * boot (the boot restore, when this app was the source at shutdown and was playing) and when media
 * power returns, through `MediaConnectorService` — about 40 s before the phone is connected (drive
 * log 2026-10-25 22:06), when there is nothing to send it to. It is remembered here ([deferred]) and
 * delivered once there is. Or the driver asked for it: the "Resume music when the phone connects"
 * setting ([resumeOnConnect]), which does what a native CarPlay head unit does on source select.
 *
 * Either way exactly one Play goes, and only if the phone reports its music stopped or paused; a
 * phone already playing gets nothing, and the remembered request is dropped. Not thread-safe: the
 * main thread owns it, like the rest of the now-playing merge.
 */
class ConnectPlay(
    private val resumeOnConnect: () -> Boolean,
) {
    private var deferredFrom: String? = null
    private var armed = false

    /** Whether a car `play()` is waiting for a session. */
    val hasDeferred: Boolean get() = deferredFrom != null

    /** The car asked for play while there was no CarPlay session to send it to; [from] is the requester. */
    fun defer(from: String) {
        deferredFrom = from
    }

    /** A session has begun: the phone's first status decides. */
    fun arm() {
        armed = true
    }

    /** The session ended before any status came; a deferred request stays for the next one. */
    fun disarm() {
        armed = false
    }

    /**
     * The phone's `playbackStatus` arrived. The reason to press Play now, for the log, or null for
     * nothing to send. Decides once per session, on the first status.
     */
    fun onStatus(status: Int): String? {
        if (!armed) return null
        armed = false
        val from = deferredFrom
        deferredFrom = null
        if (!PlayStatus.stopped(status)) return null
        return when {
            from != null -> "deferred play from $from delivered"
            resumeOnConnect() -> "resume on connect"
            else -> null
        }
    }
}
