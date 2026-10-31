package com.carlink.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The line under "Lyrics on this head unit": the songs kept and their size, as Settings says it. */
class LyricsGroupTest {
    @Test
    fun `the line says how many songs are kept, their size, and for how long`() {
        assertEquals("Reading…", usageLine(null))
        assertEquals("None kept yet. Kept until they fill 100 MB, then the least played go first.", usageLine(0 to "0 B"))
        assertEquals("1 song, 4.1 kB. Kept until they fill 100 MB, then the least played go first.", usageLine(1 to "4.1 kB"))
        assertEquals("12 songs, 48 kB. Kept until they fill 100 MB, then the least played go first.", usageLine(12 to "48 kB"))
    }
}
