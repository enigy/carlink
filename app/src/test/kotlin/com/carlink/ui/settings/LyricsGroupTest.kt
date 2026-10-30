package com.carlink.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The line under "Lyrics on this head unit": the songs kept and their size, as Settings says it. */
class LyricsGroupTest {
    @Test
    fun `the line says how many songs are kept, their size, and for how long`() {
        assertEquals("Reading…", usageLine(null))
        assertEquals("None kept yet. Each is kept 30 days, 500 MB in all at most.", usageLine(0 to "0 B"))
        assertEquals("1 song, 4.1 kB. Each is kept 30 days, 500 MB in all at most.", usageLine(1 to "4.1 kB"))
        assertEquals("12 songs, 48 kB. Each is kept 30 days, 500 MB in all at most.", usageLine(12 to "48 kB"))
    }
}
