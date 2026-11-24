package com.carlink.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.carlink.link.SetupOverNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Adapter connection › Set up over the adapter's Wi-Fi: the one question, the code at a glance, its two answers.
 * On the truck's panel as Carlink logs it (2400x960 at 200 dpi: 1920x768 dp).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1920dp-h768dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupCodeDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val answers = mutableListOf<Boolean>()

    private fun show(networkName: String?) {
        compose.setContent { MaterialTheme { SetupCodeDialog(SetupOverNetwork.Confirm("2092", networkName)) { answers += it } } }
    }

    /** The code is whole above the answers: the dialog's text never pushes it out of sight. */
    private fun assertCodeAboveTheAnswers() {
        val code = compose.onNodeWithText("2092").assertIsDisplayed()
        val match = compose.onNodeWithText(WifiLinkText.CODE_MATCH).assertIsDisplayed()
        val codeBounds = code.fetchSemanticsNode().boundsInWindow
        val matchBounds = match.fetchSemanticsNode().boundsInWindow
        assertTrue("code $codeBounds, answer $matchBounds", codeBounds.bottom <= matchBounds.top)
    }

    @Test
    fun `the code shows large under where to find it, and each answer goes back as given`() {
        show("CarLink-Setup-1a2b")
        compose.onNodeWithText(WifiLinkText.CODE_TITLE).assertIsDisplayed()
        compose.onNodeWithText(WifiLinkText.codeText("2092", "CarLink-Setup-1a2b")).assertIsDisplayed()
        // Readable at a glance from the driver's seat: 80 sp digits on an 88 sp line, beside the dialog's 22 sp text.
        compose.onNodeWithText("2092").assertHeightIsAtLeast(80.dp)
        assertCodeAboveTheAnswers()
        compose.onNodeWithText(WifiLinkText.CODE_MATCH).performClick()
        compose.onNodeWithText(WifiLinkText.CODE_NO_MATCH).performClick()
        assertEquals(listOf(true, false), answers)
    }

    @Test
    fun `a network Android won't name adds its check to the same dialog, and the code stays in sight`() {
        show(null)
        compose.onNodeWithText(WifiLinkText.codeText("2092", null)).assertIsDisplayed()
        assertCodeAboveTheAnswers()
    }
}
