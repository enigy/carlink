package com.carlink.media

/**
 * The phone's `playbackStatus` (iAP2 NowPlaying) as the session's PLAYING bit. A seek (3/4) is a
 * position change inside whatever state the phone was already in, not a pause: the phone emits a run
 * of them on every track change, and reporting them as PAUSED left AAOS routing the steering-wheel
 * keys away from this app for 17-24 s of audible music (2026-10-23).
 */
object PlayStatus {
    const val STOP = 0
    const val PLAY = 1
    const val PAUSE = 2

    /** Whether the phone is playing after [status]; [last] stands when the status says nothing about it. */
    fun playing(
        status: Int?,
        last: Boolean,
    ): Boolean =
        when (status) {
            PLAY -> true
            STOP, PAUSE -> false
            else -> last
        }

    /** The phone itself said stop or pause: definitive, and overrides audible audio still in flight. */
    fun stopped(status: Int?): Boolean = status == STOP || status == PAUSE
}
