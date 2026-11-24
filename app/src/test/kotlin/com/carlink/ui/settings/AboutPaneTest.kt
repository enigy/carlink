package com.carlink.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.carlink.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › About (owner, 24 Nov: split out of Adapter and about): the app's version, the privacy policy and the lyrics
 * group, on the truck's panel as Carlink logs it (2400x960 at 200 dpi: 1920x768 dp).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1920dp-h768dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutPaneTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `About holds the app's version, the privacy policy and the lyrics group`() {
        compose.setContent { MaterialTheme { AboutPane() } }
        listOf(
            "About",
            "App version",
            BuildConfig.VERSION_NAME,
            "Privacy policy",
            "github.com/enigy/carlink/blob/main/PRIVACY.md",
            "Look up lyrics",
            "Lyrics source",
            "LRCLIB (lrclib.net)",
            "Lyrics on this head unit",
        ).forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `the list's line for About gives the version, then what else About holds`() {
        assertEquals("Version 3.31 · Privacy and lyrics", AboutText.summary("3.31"))
        assertEquals("a version the package manager can't give is left out", "Privacy and lyrics", AboutText.summary(null))
    }
}
