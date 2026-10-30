package com.carlink.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The head unit's lyrics: kept whole, found by song, aged out after 30 days, and held under its size by dropping the oldest. */
class LyricsDiskCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_761_700_000_000L // 2025-10-29, any fixed instant
    private val day = 24L * 60 * 60 * 1000
    private val song = LyricsKey("Dream Team", "Of Monsters and Men", "Fever Dream", 189)
    private val lrc = "[00:12.30] We're the dream team\n[00:15.80] Hold on tight"

    private fun cache(
        maxBytes: Long = LyricsDiskCache.MAX_BYTES,
        dir: File = File(tmp.root, "lyrics"),
    ) = LyricsDiskCache(dir, clock = { now }, maxBytes = maxBytes)

    private fun entries(dir: File = File(tmp.root, "lyrics")) = dir.listFiles()?.filter { it.name.endsWith(".json") }.orEmpty()

    @Test
    fun `words kept for a song are found for that song only`() {
        val c = cache()
        assertTrue(c.write(song, lrc))
        assertEquals(lrc, c.read(song))
        assertNull("another length is another recording", c.read(song.copy(durationSec = 300)))
        assertNull(c.read(song.copy(artist = "Chris Taylor")))
        assertEquals("the album is not part of the song's name here", lrc, c.read(song.copy(album = "Dream Team (Live)")))
        assertTrue("one file per song", entries().size == 1)
    }

    @Test
    fun `a new process finds what an earlier one kept`() {
        cache().write(song, lrc)
        assertEquals(lrc, cache().read(song))
    }

    @Test
    fun `an entry cut short or changed is not taken, and goes`() {
        val c = cache()
        c.write(song, lrc)
        val file = entries().single()
        file.writeText(file.readText().dropLast(20))
        assertNull("cut short: not JSON any more", c.read(song))
        assertFalse(file.exists())

        c.write(song, lrc)
        val entry = JSONObject(entries().single().readText()).put("lrc", "$lrc\n[00:20.00] one line more")
        entries().single().writeText(entry.toString())
        assertNull("its words no longer match their SHA-256", c.read(song))
        assertTrue(entries().isEmpty())
    }

    @Test
    fun `words with no timed lines are not lyrics`() {
        val c = cache()
        assertTrue(c.write(song, "just words, no times"))
        assertNull(c.read(song))
    }

    @Test
    fun `entries go after thirty days`() {
        val c = cache()
        c.write(song, lrc)
        now += 29 * day
        assertEquals(lrc, c.read(song))
        now += 2 * day
        assertNull(c.read(song))
        assertTrue("an old entry is deleted when read", entries().isEmpty())

        val other = song.copy(title = "Little Talks", durationSec = 266)
        c.write(other, lrc)
        now += 31 * day
        c.prune()
        assertTrue("and when the folder is tidied", entries().isEmpty())
    }

    @Test
    fun `past its size the oldest entries go first`() {
        val probe = cache(dir = File(tmp.root, "probe"))
        probe.write(song, lrc)
        val one = probe.sizeBytes()
        val c = cache(maxBytes = one * 2 + one / 2)
        val songs = (1..3).map { song.copy(title = "Song $it") }
        songs.forEach {
            c.write(it, lrc)
            now += 60_000
        }
        assertNull("the first written went to make room", c.read(songs[0]))
        assertEquals(lrc, c.read(songs[1]))
        assertEquals(lrc, c.read(songs[2]))
        assertTrue(c.sizeBytes() <= one * 2 + one / 2)
    }

    @Test
    fun `a temp file a power loss left behind is not an entry, and is tidied away`() {
        val dir = File(tmp.root, "lyrics").apply { mkdirs() }
        File(dir, "0123.json.tmp").writeText("{\"v\":1,\"lrc\":\"[00:01.00] half")
        val c = cache()
        assertNull(c.read(song))
        assertFalse("the first use tidies the folder", File(dir, "0123.json.tmp").exists())
    }
}
