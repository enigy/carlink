package com.carlink.media

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Lyrics kept on the head unit (owner, 10-29), so a song heard before shows its words without asking
 * LRCLIB, which answered HTTP 503 for most songs of the 10-29 drive. One file per song (its title, artist
 * and length, as [LyricsKey] names it), written whole, a temp file renamed into place, with the SHA-256
 * of its words: a file a power loss cut short is never taken for lyrics, since a read checks it and
 * deletes what fails. Only found lyrics are kept; "none" stays in memory ([LrcLib]), so a song LRCLIB
 * lacks today is asked again on a later drive.
 *
 * An entry goes [maxAgeMs] after it was written (30 days); past [maxBytes] (500 MB) the oldest go first.
 * The folder is tidied the first time it is used in a process and after each write. Blocking file IO:
 * call off the main thread.
 */
class LyricsDiskCache(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val lock = Any()
    private var tidied = false

    /** The words kept for [key]: a whole entry for this song, younger than [maxAgeMs]. Null otherwise, and a bad entry is deleted. */
    fun read(key: LyricsKey): String? =
        synchronized(lock) {
            tidyOnce()
            val file = fileFor(key)
            if (!file.isFile) return null
            val lrc =
                try {
                    whole(JSONObject(file.readText()), key)
                } catch (_: IOException) {
                    null
                } catch (_: JSONException) {
                    null
                }
            if (lrc == null) file.delete()
            lrc
        }

    /** Keep [lrc] for [key]; false when it could not be written (and nothing half-written is left). */
    fun write(
        key: LyricsKey,
        lrc: String,
    ): Boolean =
        synchronized(lock) {
            tidyOnce()
            val now = clock()
            val entry =
                JSONObject()
                    .put("v", VERSION)
                    .put("title", key.title)
                    .put("artist", key.artist)
                    .put("durationSec", key.durationSec)
                    .put("fetchedAt", now)
                    .put("sha256", sha256(lrc))
                    .put("lrc", lrc)
            val file = fileFor(key)
            val tmp = File(dir, file.name + TMP)
            val written =
                try {
                    dir.mkdirs()
                    tmp.writeText(entry.toString())
                    val moved = tmp.renameTo(file)
                    if (moved) file.setLastModified(now)
                    moved
                } catch (_: IOException) {
                    false
                }
            if (!written) tmp.delete()
            prune()
            written
        }

    /** The folder's size in bytes: what [maxBytes] is held against. */
    fun sizeBytes(): Long = synchronized(lock) { dir.listFiles()?.sumOf { it.length() } ?: 0L }

    /** What Settings › Adapter and about shows: how many songs are kept, and the bytes they take. */
    data class Usage(
        val songs: Int,
        val bytes: Long,
    )

    fun usage(): Usage =
        synchronized(lock) {
            val files = dir.listFiles().orEmpty()
            Usage(files.count { it.name.endsWith(EXT) }, files.sumOf { it.length() })
        }

    /** Delete every kept song (Settings' Clear): each is looked up again the next time it plays. Returns how many went. */
    fun clear(): Int =
        synchronized(lock) {
            val files = dir.listFiles().orEmpty()
            files.forEach { it.delete() }
            files.count { it.name.endsWith(EXT) }
        }

    /** Entries past [maxAgeMs] and stray temp files go, then the oldest entries while the folder holds more than [maxBytes]. */
    internal fun prune() {
        synchronized(lock) {
            val files = dir.listFiles()?.toMutableList() ?: return
            val now = clock()
            val gone = files.filter { it.name.endsWith(TMP) || now - it.lastModified() > maxAgeMs }
            gone.forEach { it.delete() }
            files.removeAll(gone)
            var total = files.sumOf { it.length() }
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= maxBytes) break
                total -= f.length()
                f.delete()
            }
        }
    }

    private fun tidyOnce() {
        if (tidied) return
        tidied = true
        prune()
    }

    /** The entry's words when it is whole and current and for [key]; null otherwise. */
    private fun whole(
        entry: JSONObject,
        key: LyricsKey,
    ): String? {
        val forKey = entry.optString("title") == key.title && entry.optString("artist") == key.artist && entry.optLong("durationSec", -1) == key.durationSec
        val current = clock() - entry.optLong("fetchedAt", 0) <= maxAgeMs
        val lrc = entry.optString("lrc")
        val intact = lrc.isNotEmpty() && sha256(lrc) == entry.optString("sha256")
        val ok = entry.optInt("v") == VERSION && forKey && current && intact && SyncedLyrics.parse(lrc) != null
        return if (ok) lrc else null
    }

    private fun fileFor(key: LyricsKey): File = File(dir, sha256("${key.title}\n${key.artist}\n${key.durationSec}") + EXT)

    companion object {
        /** Thirty days, then an entry is asked for again. */
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        /** 500 MB, then the oldest entries make room. An entry is a few KB. */
        const val MAX_BYTES = 500L * 1024 * 1024

        private const val VERSION = 1
        private const val EXT = ".json"
        private const val TMP = ".tmp"
        private const val FOLDER = "lyrics"

        @Volatile private var instance: LyricsDiskCache? = null

        /** The app's cache, in its no-backup files folder: kept across drives, never copied off the head unit. */
        fun get(context: Context): LyricsDiskCache =
            instance ?: synchronized(this) {
                instance ?: LyricsDiskCache(File(context.applicationContext.noBackupFilesDir, FOLDER)).also { instance = it }
            }

        internal fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }

        private const val BYTE = 0xFF
        private const val HEX = 16
    }
}
