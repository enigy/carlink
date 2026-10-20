package com.carlink.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundLogoConfigTest {
    @Test
    fun `only the GMC and Chevrolet forest scenes get the fog`() {
        assertEquals(
            setOf(BackgroundLogoConfig.GMC, BackgroundLogoConfig.CHEVROLET),
            BackgroundLogoConfig.entries.filter { it.showsFog }.toSet(),
        )
    }
}
