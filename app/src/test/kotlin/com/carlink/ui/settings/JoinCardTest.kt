package com.carlink.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.carlink.link.AdapterLink
import com.carlink.link.LinkMode
import com.carlink.link.WifiLinkSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Adapter Config › Join the adapter's Wi-Fi (ADDENDUM 24 item 7): the network's name and password with the
 * steps, the plain line that the head unit has no internet over it, the password only while parked. On the truck's panel
 * as Carlink logs it (1920x768 dp).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1920dp-h768dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JoinCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val settings =
        WifiLinkSettings(link = AdapterLink.WIFI, keyStored = true, mode = LinkMode.AP, apName = "CarLink-0000", apPasswordKept = true)
    private var opened = 0

    private fun show(
        s: WifiLinkSettings = settings,
        parked: Boolean = true,
        page: Boolean = true,
    ) {
        compose.setContent {
            MaterialTheme {
                Column {
                    JoinCard(s, parked, loadPassword = { "example-pass" }, openSettings = {
                        opened++
                        page
                    })
                }
            }
        }
    }

    @Test
    fun `parked, the name and the password show with the steps and the no-internet line`() {
        show()
        compose.onNodeWithText(ApLinkText.JOIN_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ApLinkText.NO_INTERNET).assertIsDisplayed()
        compose.onNodeWithText("CarLink-0000").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("example-pass").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("example-pass").assertIsDisplayed()
        ApLinkText.JOIN_STEPS.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `while driving the password is not on screen`() {
        show(parked = false)
        compose.onNodeWithText(ApLinkText.PASSWORD_WHILE_PARKED).assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("example-pass").fetchSemanticsNodes().size)
    }

    @Test
    fun `an adapter that named no network says so, and no password is asked for`() {
        show(settings.copy(apName = "", apPasswordKept = false))
        compose.onNodeWithText(ApLinkText.NOT_NAMED).assertIsDisplayed()
        compose.onNodeWithText(ApLinkText.NO_INTERNET).assertIsDisplayed()
    }

    @Test
    fun `Open Wi-Fi settings opens the page, and where there is none says how to get there`() {
        show(page = false)
        compose.onNodeWithText(ApLinkText.OPEN_WIFI_SETTINGS).performClick()
        assertEquals(1, opened)
        compose.onNodeWithText(ApLinkText.NO_WIFI_SETTINGS).assertIsDisplayed()
    }
}
