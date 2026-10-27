package com.carlink.media

import com.carlink.BuildConfig
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** What LRCLIB is asked by: the song as the iPhone names it, its length in whole seconds (0 = unknown). */
data class LyricsKey(
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Long,
) {
    companion object {
        /** The key for [info], or null when the iPhone has not named the song and its artist. */
        fun of(info: NowPlayingInfo): LyricsKey? {
            val title = info.title?.trim().orEmpty()
            val artist = info.artist?.trim().orEmpty()
            if (title.isEmpty() || artist.isEmpty()) return null
            return LyricsKey(title, artist, info.album?.trim().orEmpty(), (info.durationMs + HALF_SECOND_MS) / MS_PER_SECOND)
        }

        private const val MS_PER_SECOND = 1_000L
        private const val HALF_SECOND_MS = 500L
    }
}

/**
 * Timed lyrics from LRCLIB (lrclib.net): free, no key, crowd-sourced, looked up by the song's title,
 * artist, album and length, which is all the iPhone sends that names it (iAP2 now-playing carries no
 * lyrics, and Spotify and Apple offer no public lyrics API; research 10-25). Only timed lyrics are
 * kept: untimed ones cannot follow the song on a two-line card.
 *
 * First the exact lookup (`/api/get`, which matches the length within a couple of seconds), then a
 * search by title and first artist, taking the timed hit whose length is closest within
 * [MATCH_SECONDS]: Spotify names live cuts and remasters by album, and a search finds the recording
 * whose length matches (owner's drive 10-26: "Dirty Paws", LIVE FROM VATNAGARÐAR, 300 s). Answers,
 * found or not, are kept for the process ([CACHE_SIZE] songs); a failed connection is not an answer
 * ([find] returns null) and the caller asks again. Blocking: call off the main thread.
 */
object LrcLib {
    /** A finished lookup: [lyrics] null when LRCLIB has none for the song. */
    class Answer(
        val lyrics: SyncedLyrics?,
    )

    private val cache =
        object : LinkedHashMap<LyricsKey, Answer>(CACHE_SIZE, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LyricsKey, Answer>?): Boolean = size > CACHE_SIZE
        }

    /** The answer already in hand for [key], or null when it has not been looked up. */
    fun cached(key: LyricsKey): Answer? = synchronized(cache) { cache[key] }

    /**
     * LRCLIB's answer for the song: its timed lyrics, or none ([Answer.lyrics] null). Null when LRCLIB
     * could not be reached or answered unreadably, which is not an answer: the caller asks again.
     */
    fun find(key: LyricsKey): Answer? {
        cached(key)?.let { return it }
        val lrc =
            try {
                lookUp(key)
            } catch (e: IOException) {
                logWarn("[LYRICS] LRCLIB unreachable for \"${key.title}\": ${e.javaClass.simpleName}: ${e.message}", tag = Logger.Tags.MEDIA)
                return null
            } catch (e: JSONException) {
                logWarn("[LYRICS] LRCLIB answer unreadable for \"${key.title}\": ${e.message}", tag = Logger.Tags.MEDIA)
                return null
            }
        val answer = Answer(lrc?.let(SyncedLyrics::parse))
        logInfo("[LYRICS] \"${key.title}\" (${key.durationSec}s): ${answer.lyrics?.let { "${it.lines.size} timed lines" } ?: "none"}", tag = Logger.Tags.MEDIA)
        synchronized(cache) { cache[key] = answer }
        return answer
    }

    /** The LRC text: the exact match's, else the closest search hit's. Null when neither has timed lyrics. */
    private fun lookUp(key: LyricsKey): String? {
        val exact =
            get(
                "get",
                "track_name" to key.title,
                "artist_name" to key.artist,
                "album_name" to key.album,
                "duration" to key.durationSec.toString(),
            )
        exact?.let { synced(JSONObject(it)) }?.let { return it }
        val hits = get("search", "track_name" to key.title, "artist_name" to firstArtist(key.artist)) ?: return null
        return pick(JSONArray(hits), key.durationSec)
    }

    /**
     * Of search [hits], the timed lyrics whose length is closest to [durationSec], within
     * [MATCH_SECONDS]; with the length unknown (0), the first timed hit.
     */
    internal fun pick(
        hits: JSONArray,
        durationSec: Long,
    ): String? {
        val timed = (0 until hits.length()).mapNotNull { i -> hits.optJSONObject(i)?.let { hit -> synced(hit)?.let { hit to it } } }
        if (durationSec <= 0) return timed.firstOrNull()?.second
        return timed
            .map { (hit, lrc) -> abs(hit.optDouble("duration", Double.NaN) - durationSec) to lrc }
            .filter { (off, _) -> off <= MATCH_SECONDS }
            .minByOrNull { (off, _) -> off }
            ?.second
    }

    /** A record's timed lyrics; null for an instrumental, or a JSON null or blank `syncedLyrics`. */
    internal fun synced(record: JSONObject): String? {
        if (record.optBoolean("instrumental", false) || record.isNull("syncedLyrics")) return null
        return record.optString("syncedLyrics").takeIf { it.isNotBlank() }
    }

    /** Spotify joins a song's artists with commas ("Of Monsters and Men, Chris Taylor"); LRCLIB files most under the first. */
    internal fun firstArtist(artist: String): String = artist.substringBefore(", ").trim()

    /** The body of `GET /api/[path]?[query]`; null for a 404 (nothing found). Throws [IOException] for anything else. */
    private fun get(
        path: String,
        vararg query: Pair<String, String>,
    ): String? {
        val q = query.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8.name())}" }
        val conn = URL("$BASE/$path?$q").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            // LRCLIB asks every client to name itself.
            conn.setRequestProperty("User-Agent", "Carlink3/${BuildConfig.VERSION_NAME} (https://github.com/enigy/carlink3)")
            return when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> conn.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("HTTP $code from /api/$path")
            }
        } finally {
            conn.disconnect()
        }
    }

    private const val BASE = "https://lrclib.net/api"
    private const val TIMEOUT_MS = 8_000
    private const val MATCH_SECONDS = 3.0
    private const val CACHE_SIZE = 64
    private const val LOAD_FACTOR = 0.75f
}
