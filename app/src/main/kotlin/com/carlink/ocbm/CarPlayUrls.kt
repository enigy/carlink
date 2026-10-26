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
}
