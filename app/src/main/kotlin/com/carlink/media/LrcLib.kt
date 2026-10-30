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
 * LRCLIB's own records first: the exact lookup in them (`/api/get-cached`, which matches the length within
 * a couple of seconds), then a search by title and first artist, taking the timed hit whose length is
 * closest within [MATCH_SECONDS]: Spotify names live cuts and remasters by album, and a search finds the
 * recording whose length matches (owner's drive 10-26: "Dirty Paws", LIVE FROM VATNAGARÐAR, 300 s). Only
 * then `/api/get`, which also asks LRCLIB's outside sources: on the 10-29 drive it answered HTTP 503 for
 * most songs, and asked first it stopped every lookup before the search was tried (two songs played
 * through without lyrics). A step that can't be reached is passed over; the lookup fails only when no
 * step found lyrics and one could not be reached.
 *
 * Answers, found or not, are kept for the process ([CACHE_SIZE] songs), and found lyrics on the head unit
 * too ([LyricsDiskCache], read before LRCLIB is asked); a failed lookup is not an answer ([find] returns
 * null) and the caller asks again. Blocking: call off the main thread.
 */
object LrcLib {
    /** A finished lookup: [lyrics] null when LRCLIB has none for the song. */
    class Answer(
        val lyrics: SyncedLyrics?,
    )

    /** How LRCLIB is reached: the body of `GET /api/[path]?[query]`, null for a 404 (nothing found); [IOException] for anything else. */
    fun interface Api {
        fun get(
            path: String,
            query: List<Pair<String, String>>,
        ): String?
    }

    private val cache =
        object : LinkedHashMap<LyricsKey, Answer>(CACHE_SIZE, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LyricsKey, Answer>?): Boolean = size > CACHE_SIZE
        }

    /** The answer already in hand for [key], or null when it has not been looked up. */
    fun cached(key: LyricsKey): Answer? = synchronized(cache) { cache[key] }

    /**
     * LRCLIB's answer for the song: its timed lyrics, or none ([Answer.lyrics] null). Null when LRCLIB
     * could not be reached or answered unreadably, which is not an answer: the caller asks again.
     *
     * Lyrics kept on the head unit ([disk]) are taken first, and found ones are kept there. [askAgain]: a
     * "none" kept for this process is asked again, because the driver just turned the lyrics on.
     */
    fun find(
        key: LyricsKey,
        disk: LyricsDiskCache? = null,
        askAgain: Boolean = false,
        api: Api = Net,
    ): Answer? {
        cached(key)?.let { if (it.lyrics != null || !askAgain) return it }
        disk?.read(key)?.let { lrc -> fromDisk(key, lrc)?.let { return it } }
        val lrc =
            try {
                lookUp(key, api)
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
        if (lrc != null && answer.lyrics != null && disk?.write(key, lrc) == false) {
            logWarn("[LYRICS] could not keep \"${key.title}\" on the head unit", tag = Logger.Tags.MEDIA)
        }
        return answer
    }

    private fun fromDisk(
        key: LyricsKey,
        lrc: String,
    ): Answer? {
        val lyrics = SyncedLyrics.parse(lrc) ?: return null
        logInfo("[LYRICS] \"${key.title}\" (${key.durationSec}s): ${lyrics.lines.size} timed lines, kept on the head unit", tag = Logger.Tags.MEDIA)
        return Answer(lyrics).also { synchronized(cache) { cache[key] = it } }
    }

    /**
     * The LRC text: from LRCLIB's own records (the exact match, else the closest search hit), else from the
     * lookup that also asks its outside sources. Null when none has timed lyrics; [IOException] when none
     * had them and a step could not be reached, since that one might have.
     */
    internal fun lookUp(
        key: LyricsKey,
        api: Api,
    ): String? {
        val exact = listOf("track_name" to key.title, "artist_name" to key.artist, "album_name" to key.album, "duration" to key.durationSec.toString())
        val missed = mutableListOf<String>()
        val ask = { path: String, query: List<Pair<String, String>> ->
            try {
                api.get(path, query)
            } catch (e: IOException) {
                missed += e.message ?: "/api/$path failed"
                null
            }
        }
        ask("get-cached", exact)?.let { synced(JSONObject(it)) }?.let { return it }
        ask("search", listOf("track_name" to key.title, "artist_name" to firstArtist(key.artist)))?.let { pick(JSONArray(it), key.durationSec) }?.let { return it }
        ask("get", exact)?.let { synced(JSONObject(it)) }?.let { return it }
        if (missed.isNotEmpty()) throw IOException(missed.joinToString("; "))
        return null
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

    /** LRCLIB over HTTPS. */
    private object Net : Api {
        override fun get(
            path: String,
            query: List<Pair<String, String>>,
        ): String? {
            val q = query.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8.name())}" }
            val conn = URL("$BASE/$path?$q").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                // LRCLIB asks every client to name itself.
                conn.setRequestProperty("User-Agent", "Carlink/${BuildConfig.VERSION_NAME} (https://github.com/enigy/carlink)")
                return when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> conn.inputStream.bufferedReader().use { it.readText() }
                    HttpURLConnection.HTTP_NOT_FOUND -> null
                    else -> throw IOException("HTTP $code from /api/$path")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private const val BASE = "https://lrclib.net/api"
    private const val TIMEOUT_MS = 8_000
    private const val MATCH_SECONDS = 3.0
    private const val CACHE_SIZE = 64
    private const val LOAD_FACTOR = 0.75f
}
