package com.carlink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.DriveSide
import com.carlink.ui.settings.LayoutConfig
import com.carlink.ui.settings.PaneLayout
import com.carlink.ui.settings.SessionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Leaving Settings after changing a setting only a new connection applies (owner, 10-28). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsExitTest {
    @get:Rule
    val compose = createComposeRule()

    private val built = SessionSettings(DisplayMode.DEFAULT, LayoutConfig(), DriveSide.LEFT, askLanes = false)
    private var saved = built
    private var session: SessionSettings? = built
    private val exit = SettingsExit(saved = { saved }, built = { session })

    @Test
    fun `nothing changed closes at once`() {
        exit.opened()
        assertFalse(exit.holdForAnswer())
    }

    @Test
    fun `a changed seat holds the close for the question`() {
        exit.opened()
        saved = built.copy(driveSide = DriveSide.RIGHT)
        assertTrue(exit.holdForAnswer())
        assertEquals(listOf(SessionSettings.DRIVER_SEAT), exit.asking)
    }

    @Test
    fun `with no session the change simply waits for the next connection`() {
        exit.opened()
        session = null
        saved = built.copy(driveSide = DriveSide.RIGHT)
        assertFalse(exit.holdForAnswer())
    }

    @Test
    fun `the question names the settings and what a reset costs`() {
        assertEquals(
            "Driver's seat takes effect at a new connection. Reset now to apply it (CarPlay is gone for about 10 seconds), " +
                "or keep it saved for the next connection.",
            SettingsExit.question(listOf(SessionSettings.DRIVER_SEAT)),
        )
        assertTrue(SettingsExit.question(listOf("Display mode", "Layout", "Driver's seat")).startsWith("Display mode, Layout and Driver's seat take effect"))
    }

    @Test
    fun `Later closes Settings and keeps the change, Reset now resets`() {
        var later = 0
        var reset = 0
        compose.setContent { MaterialTheme { SettingsExitPrompt(exit, onReset = { reset++ }, onLater = { later++ }) } }
        exit.opened()
        saved = built.copy(layout = LayoutConfig(PaneLayout.MEDIA_RIGHT))
        compose.runOnIdle { exit.holdForAnswer() }
        compose.onNodeWithText("Later").performClick()
        compose.runOnIdle { assertEquals(1 to 0, later to reset) }
        compose.onNodeWithText("Reset now").assertDoesNotExist()
        compose.runOnIdle { exit.holdForAnswer() }
        compose.onNodeWithText("Reset now").performClick()
        compose.runOnIdle { assertEquals(1 to 1, later to reset) }
        assertEquals(emptyList<String>(), exit.asking)
    }
}
