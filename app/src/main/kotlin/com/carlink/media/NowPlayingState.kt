package com.carlink.media

import com.carlink.ocbm.Ocbm
import java.util.Locale

/**
 * What the phone is playing, as one value for the split layout's media card: the track
 * ([NowPlayingInfo], the same merge the media session publishes), whether it is playing, and the last
 * position the iPhone reported with the moment it arrived. iOS sends the position about twice a
 * second; [positionAt] carries it forward between ticks so the progress bar moves smoothly.
 */
data class NowPlayingState(
    val info: NowPlayingInfo,
    val playing: Boolean,
    /** The iPhone's last reported elapsed time, ms. */
    val positionMs: Long,
    /** `SystemClock.elapsedRealtime()` when [positionMs] arrived; 0 = never. */
    val positionAtMs: Long,
) {
    /** A track worth a card: iOS has named it. */
    val hasTrack: Boolean get() = !info.title.isNullOrBlank()

    /** The position at [nowMs] (elapsedRealtime), run forward while playing and held inside the song. */
    fun positionAt(nowMs: Long): Long {
        val p = if (playing && positionAtMs > 0L) positionMs + (nowMs - positionAtMs) else positionMs
        val floor = p.coerceAtLeast(0L)
        return if (info.durationMs > 0L) floor.coerceAtMost(info.durationMs) else floor
    }

    companion object {
        val NONE = NowPlayingState(NowPlayingInfo(title = null, artist = null, album = null, appName = null), playing = false, positionMs = 0L, positionAtMs = 0L)

        private const val MS_PER_SECOND = 1_000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val MINUTES_PER_HOUR = 60L

        /** `m:ss`, or `h:mm:ss` from an hour on, for a progress bar's ends. Negative reads as 0:00. */
        fun clock(ms: Long): String {
            val total = (ms / MS_PER_SECOND).coerceAtLeast(0L)
            val s = total % SECONDS_PER_MINUTE
            val m = total / SECONDS_PER_MINUTE % MINUTES_PER_HOUR
            val h = total / SECONDS_PER_MINUTE / MINUTES_PER_HOUR
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}

/** The three transport buttons the media card has, as the HID index each sends ([Ocbm.INPUT_MEDIA_BTN]). */
enum class MediaControl(
    val button: Byte,
) {
    PREVIOUS(Ocbm.MEDIA_BTN_PREV),
    PLAY_PAUSE(Ocbm.MEDIA_BTN_PLAY_PAUSE),
    NEXT(Ocbm.MEDIA_BTN_NEXT),
}
