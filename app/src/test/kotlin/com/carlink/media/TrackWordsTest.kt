package com.carlink.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The now-playing words as a delta stream: a left-out field stands, an empty one clears. */
class TrackWordsTest {
    private var artist: String? = null
    private var album: String? = null

    private fun frame(json: String) {
        val f = JSONObject(json)
        artist = TrackWords.after(f, "artist", artist)
        album = TrackWords.after(f, "album", album)
    }

    @Test
    fun `the 11-03 Black Water change keeps the artist and the album sent before the title`() {
        frame("""{"title":"Vulture, Vulture","artist":"Of Monsters and Men"}""")
        // Skip to Black Water: the album first, then the title alone (same artist as the song before).
        frame("""{"album":"Beneath The Skin (Remastered)","durationMs":253133}""")
        frame("""{"title":"Black Water"}""")
        assertEquals("Of Monsters and Men", artist)
        assertEquals("Beneath The Skin (Remastered)", album)
    }

    @Test
    fun `a new artist replaces the last`() {
        frame("""{"title":"Black Water","artist":"Of Monsters and Men","album":"Beneath The Skin (Remastered)"}""")
        frame("""{"album":"A Place We Knew"}""")
        frame("""{"title":"Waves","artist":"Dean Lewis"}""")
        assertEquals("Dean Lewis", artist)
        assertEquals("A Place We Knew", album)
    }

    @Test
    fun `an empty field clears it`() {
        frame("""{"title":"Luck","artist":"American Authors","album":"Oh, What A Life"}""")
        frame("""{"title":"","artist":"","album":""}""")
        assertNull(artist)
        assertNull(album)
    }

    @Test
    fun `a position tick changes nothing`() {
        frame("""{"title":"Luck","artist":"American Authors","album":"Oh, What A Life"}""")
        frame("""{"elapsedMs":12000}""")
        assertEquals("American Authors", artist)
        assertEquals("Oh, What A Life", album)
    }
}
