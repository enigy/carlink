package com.carlink.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrcLibTest {
    // Shaped like LRCLIB's search for "Dirty Paws" by Of Monsters and Men (10-27): one recording per length.
    private val hits =
        JSONArray(
            """
            [
              {"id": 1, "albumName": "Live From Vatnagardar (EP)", "duration": 300.0, "instrumental": false, "syncedLyrics": "[00:18.69] live"},
              {"id": 2, "albumName": "Indie/Rock Playlist", "duration": 274.0, "instrumental": false, "syncedLyrics": "[00:10.00] playlist"},
              {"id": 3, "albumName": "My Head Is an Animal", "duration": 266.0, "instrumental": false, "syncedLyrics": null, "plainLyrics": "untimed"},
              {"id": 4, "albumName": "My Head Is An Animal (10th)", "duration": 266.0, "instrumental": false, "syncedLyrics": "[00:12.00] album"},
              {"id": 5, "albumName": "Karaoke", "duration": 299.0, "instrumental": true, "syncedLyrics": "[00:01.00] none"}
            ]
            """.trimIndent(),
        )

    @Test
    fun `the search hit whose length matches the song is taken`() {
        // The owner's 10-26 drive: the live cut, 300.5 s.
        assertEquals("[00:18.69] live", LrcLib.pick(hits, durationSec = 301))
        assertEquals("the timed one of two at that length", "[00:12.00] album", LrcLib.pick(hits, durationSec = 266))
        assertNull("nothing within 3 s", LrcLib.pick(hits, durationSec = 240))
    }

    @Test
    fun `with the length unknown the first timed hit is taken`() {
        assertEquals("[00:18.69] live", LrcLib.pick(hits, durationSec = 0))
    }

    @Test
    fun `instrumentals, JSON nulls and blanks have no timed lyrics`() {
        assertNull(LrcLib.synced(JSONObject("""{"instrumental": true, "syncedLyrics": "[00:01.00] x"}""")))
        assertNull(LrcLib.synced(JSONObject("""{"instrumental": false, "syncedLyrics": null}""")))
        assertNull(LrcLib.synced(JSONObject("""{"instrumental": false, "syncedLyrics": "  "}""")))
        assertEquals("[00:01.00] x", LrcLib.synced(JSONObject("""{"syncedLyrics": "[00:01.00] x"}""")))
    }

    @Test
    fun `the song is asked for by its first artist and its length in whole seconds`() {
        assertEquals("Of Monsters and Men", LrcLib.firstArtist("Of Monsters and Men, Chris Taylor"))
        val info = NowPlayingInfo(title = " Dirty Paws ", artist = "Of Monsters and Men", album = "LIVE FROM VATNAGARÐAR", appName = "Spotify", durationMs = 300_506L)
        assertEquals(LyricsKey("Dirty Paws", "Of Monsters and Men", "LIVE FROM VATNAGARÐAR", 301L), LyricsKey.of(info))
        assertNull("no artist yet", LyricsKey.of(info.copy(artist = null)))
    }
}
