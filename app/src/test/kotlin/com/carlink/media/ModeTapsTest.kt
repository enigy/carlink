package com.carlink.media

import com.carlink.media.ModeTaps.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a shuffle or repeat tap goes: the key for apps that take it, Now Playing otherwise (Mac bench 10-27). */
class ModeTapsTest {
    @Test
    fun `apple music takes the keys, spotify gets now playing`() {
        assertEquals(Route.KEY, ModeTaps.route("Music", mode = 0))
        assertEquals(Route.KEY, ModeTaps.route("Music", mode = 2))
        assertEquals("Spotify ignores Random Play and Repeat", Route.NOW_PLAYING, ModeTaps.route("Spotify", mode = 1))
    }

    @Test
    fun `a mode the phone never reported goes to now playing`() {
        assertEquals(Route.NOW_PLAYING, ModeTaps.route("Music", mode = null))
    }

    @Test
    fun `spotify is known by the app name the phone reports`() {
        assertTrue(ModeTaps.isSpotify("Spotify"))
        assertTrue(ModeTaps.isSpotify(" spotify "))
        assertFalse(ModeTaps.isSpotify("Music"))
        assertFalse(ModeTaps.isSpotify(null))
    }
}
