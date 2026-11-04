package com.carlink.media

import org.json.JSONObject

/**
 * The words of the iPhone's now-playing (artist, album, genre, composer) after one frame. iAP2 NowPlaying
 * sends only what changed: a field the frame leaves out stands, and one sent empty is cleared. A new title
 * does not reset them. On a track change the phone sends the new album about half a second before the new
 * title, and the artist only when it differs from the last song's, so resetting on the title lost every
 * album and the artist of each song by the same artist as the one before (11-03 drive: "Black Water" after
 * "Skeletons", both Of Monsters and Men, played with no artist and no album).
 */
object TrackWords {
    /** [key] after [frame], from [kept], the value before it. */
    fun after(
        frame: JSONObject,
        key: String,
        kept: String?,
    ): String? = if (!frame.has(key)) kept else frame.optString(key).ifEmpty { null }
}
