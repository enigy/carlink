package com.carlink.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** The outage [LrcLib.find] reports for song [name] when LRCLIB answers each path from [answers] (an [IOException] is thrown); null when it answered. */
    private fun outageOf(
        name: String,
        answers: Map<String, Any?>,
    ): LrcLib.Outage? {
        var why: LrcLib.Outage? = null
        val api = LrcLib.Api { path, _ -> answers[path].let { if (it is IOException) throw it else it as String? } }
        val answer = LrcLib.find(song(name), api = api) { why = it }
        assertTrue("an outage is not an answer", answer == null || why == null)
        return why
    }

    @Test
    fun `the card hears whether LRCLIB was out of reach or reached and failing`() {
        // The 10-31 drive: lrclib.net not found as the truck's data link came up, then HTTP 502 all afternoon.
        val offline = IOException("Unable to resolve host \"lrclib.net\": No address associated with hostname")
        assertEquals(LrcLib.Outage.NO_CONNECTION, outageOf("offline", mapOf("get-cached" to offline, "search" to offline, "get" to offline)))
        val down = LrcLib.HttpError(502, "get-cached")
        assertEquals(LrcLib.Outage.NOT_ANSWERING, outageOf("down", mapOf("get-cached" to down, "search" to down, "get" to down)))
        // The morning: the exact lookup answered "not here", the search and the outside lookup HTTP 503.
        val busy = LrcLib.HttpError(503, "search")
        val struggling = outageOf("struggling", mapOf("get-cached" to null, "search" to busy, "get" to busy))
        assertEquals("a step that answered shows LRCLIB was reached", LrcLib.Outage.NOT_ANSWERING, struggling)
        assertEquals(LrcLib.Outage.NOT_ANSWERING, outageOf("unreadable", mapOf("get-cached" to "<html>")))
        assertNull("found: nothing to report", outageOf("found", mapOf("get-cached" to record)))
        assertNull("none: nothing to report", outageOf("none here", emptyMap()))
    }

    @Test
    fun `turning the lyrics on asks again for a song that had none`() {
        val key = song("none at first")
        assertNull(LrcLib.find(key, api = answering(null))?.lyrics)
        assertNull("kept for the process", LrcLib.find(key, api = answering(record))?.lyrics)
        assertNotNull("asked again when the driver turns them on", LrcLib.find(key, askAgain = true, api = answering(record))?.lyrics)
    }
}
