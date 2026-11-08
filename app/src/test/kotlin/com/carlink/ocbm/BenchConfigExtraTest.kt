package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Test

/** The debug build's bench levers: only OCBM's bench keys, one line each, so nothing else reaches the pushed YAML. */
class BenchConfigExtraTest {
    @Test
    fun `only the bench keys are kept, the last of each winning`() {
        val text =
            """
            # dev adapter, 8 Nov
            source_version: "509.11"
            display_panels: true
            name: Hijack
            source_version: "950.7.1"
            accessoryConfig:
              enablesHEVC: true
            display_panels: true # comment
            """.trimIndent()
        val (kept, dropped) = BenchConfigExtra.lines(text)
        assertEquals(listOf("source_version: \"950.7.1\"", "display_panels: true"), kept)
        assertEquals(listOf("name: Hijack", "accessoryConfig:", "enablesHEVC: true", "display_panels: true # comment"), dropped)
    }

    @Test
    fun `a value that isn't a short plain token is dropped`() {
        assertEquals(emptyList<String>(), BenchConfigExtra.lines("source_version: \"9.1\" extra\ndisplay_panels: [true]\n").first)
    }
}
