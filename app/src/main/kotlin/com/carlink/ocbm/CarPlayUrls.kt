package com.carlink.ocbm

/**
 * CarPlay app URLs for `requestUI` ([Ocbm.CMD_REQUEST_UI_URL]), benched on the truck 2026-10-26 with
 * box cbdd60b: these three bring their app forward on the main screen; `music:` does nothing.
 */
object CarPlayUrls {
    /** Maps. */
    const val MAPS = "maps:"

    /** The app that is playing now (Spotify, Music, …): its Now Playing screen. */
    const val NOW_PLAYING = "nowplaying:"

    /** The Phone app. */
    const val PHONE = "mobilephone:"

    /**
     * How long after a call reaches the pane before CarPlay is asked back to what it was showing:
     * iOS pulls its Phone app forward on its own as the call begins, and asking before it has done
     * so would be undone by it.
     */
    const val CALL_RETURN_DELAY_MS = 1_500L
}
