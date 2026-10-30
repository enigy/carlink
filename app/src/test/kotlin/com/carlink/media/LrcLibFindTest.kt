package com.carlink.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/** A song's lyrics from the head unit's copies before LRCLIB, kept once found, and asked again when the driver turns lyrics on. */
@RunWith(RobolectricTestRunner::class)
class LrcLibFindTest {
    @get:Rule val tmp = TemporaryFolder()

    private val lrc = "[00:05.00] come together\n[00:09.00] right now"
    private val record = """{"id": 9, "duration": 134.0, "instrumental": false, "syncedLyrics": "[00:05.00] come together\n[00:09.00] right now"}"""

    /** Each test its own song: [LrcLib] keeps answers for the process. */
    private fun song(name: String) = LyricsKey(name, "Matt and Kim", "Lightning", 134)

    private fun disk() = LyricsDiskCache(File(tmp.root, "lyrics"))

    private val unreachable = LrcLib.Api { path, _ -> throw IOException("HTTP 503 from /api/$path") }

    private fun answering(body: String?) = LrcLib.Api { path, _ -> if (path == "get-cached") body else null }

    @Test
    fun `lyrics kept on the head unit are taken without asking LRCLIB`() {
        val key = song("kept on the head unit")
        val d = disk()
        d.write(key, lrc)
        val answer = LrcLib.find(key, d, api = { _, _ -> throw AssertionError("LRCLIB asked for a song kept on the head unit") })
        assertEquals(2, answer?.lyrics?.lines?.size)
    }

    @Test
    fun `lyrics found are kept on the head unit for the next drive`() {
        val key = song("found and kept")
        val d = disk()
        assertNotNull(LrcLib.find(key, d, api = answering(record))?.lyrics)
        assertEquals(lrc, d.read(key))
    }

    @Test
    fun `none is not kept on the head unit, and LRCLIB out of reach is no answer`() {
        val d = disk()
        val none = song("none")
        assertNull(LrcLib.find(none, d, api = answering(null))?.lyrics)
        assertNull(d.read(none))
        assertNull("not an answer: asked again", LrcLib.find(song("out of reach"), d, api = unreachable))
    }

    @Test
    fun `turning the lyrics on asks again for a song that had none`() {
        val key = song("none at first")
        assertNull(LrcLib.find(key, api = answering(null))?.lyrics)
        assertNull("kept for the process", LrcLib.find(key, api = answering(record))?.lyrics)
        assertNotNull("asked again when the driver turns them on", LrcLib.find(key, askAgain = true, api = answering(record))?.lyrics)
    }
}
