package com.carlink.media

/**
 * Where the pane's shuffle and repeat taps go (box b7144f0, Mac bench 2026-10-27). The media remote's
 * Random Play and Repeat keys ([Route.KEY]) reach the playing app's own changeShuffleMode and
 * changeRepeatMode, and Apple Music cycles both. Otherwise CarPlay's Now Playing comes up
 * ([Route.NOW_PLAYING]), where the app's own button works. That covers Spotify, which ignores both keys
 * and draws its own shuffle (Smart Shuffle included) and repeat in CarPlay, and any app whose mode the
 * phone has not reported, since the pane could not tell whether a key took.
 */
object ModeTaps {
    enum class Route { KEY, NOW_PLAYING }

    /** How long a key has to move the mode before Now Playing comes up instead. */
    const val WAIT_MS = 2_000L

    fun route(
        appName: String?,
        mode: Int?,
    ): Route = if (isSpotify(appName) || mode == null) Route.NOW_PLAYING else Route.KEY

    /** The playing app is Spotify, as iAP2 names it (PlaybackAttributes AppName). */
    fun isSpotify(appName: String?): Boolean = appName?.trim()?.equals(SPOTIFY, ignoreCase = true) == true

    private const val SPOTIFY = "Spotify"
}
