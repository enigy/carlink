package com.carlink.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings › Phones › Resume music when the phone connects. On (the default): when the phone
 * connects and reports its music stopped or paused, Carlink3 presses Play for it once
 * ([com.carlink.media.ConnectPlay]), which is what a native CarPlay head unit does on source select
 * and what the driver expects from the car's own CarPlay. Off: the music waits for the driver, as in
 * 1.01. App-wide, not per iPhone.
 *
 * Lyrics: the music card's own switch in the pane's header ([com.carlink.ui.pane.LyricsToggle]), kept
 * here. On (the default): the pane looks the song up on LRCLIB ([com.carlink.media.LrcLib]) and shows
 * its words in time with it. Off: nothing leaves the car.
 */
class MusicStore private constructor(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val resume = MutableStateFlow(prefs.getBoolean(KEY_RESUME, true))
    private val lyrics = MutableStateFlow(prefs.getBoolean(KEY_LYRICS, true))

    val resumeOnConnectFlow: StateFlow<Boolean> = resume.asStateFlow()

    /** Whether Play is pressed for a phone that connects with its music stopped. */
    val resumeOnConnect: Boolean get() = resume.value

    fun setResumeOnConnect(on: Boolean) {
        prefs.edit().putBoolean(KEY_RESUME, on).apply()
        resume.value = on
    }

    /** Whether the music card looks up and shows the song's lyrics. */
    val lyricsFlow: StateFlow<Boolean> = lyrics.asStateFlow()

    fun setLyrics(on: Boolean) {
        prefs.edit().putBoolean(KEY_LYRICS, on).apply()
        lyrics.value = on
    }

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these orphans the stored choice.
        private const val PREFS_NAME = "carlink_music"
        private const val KEY_RESUME = "resume_on_connect"
        private const val KEY_LYRICS = "lyrics"

        @Volatile private var instance: MusicStore? = null

        fun get(context: Context): MusicStore =
            instance ?: synchronized(this) {
                instance ?: MusicStore(context).also { instance = it }
            }
    }
}
