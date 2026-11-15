package com.carlink.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import com.carlink.CarlinkManager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Phones' rank arrows: buttons, the first phone's up and the last one's down off (the Mac's round 6, UI-1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RankArrowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val first = CarlinkManager.DeviceInfo(btMac = "0a:00:00:00:00:01", name = "First iPhone", type = "iPhone")
    private val second = CarlinkManager.DeviceInfo(btMac = "0a:00:00:00:00:02", name = "Second iPhone", type = "iPhone")
    private val moves = mutableListOf<Pair<String, Int>>()

    private fun show() =
        compose.setContent {
            MaterialTheme {
                KnownPhones(
                    usbInUse = false,
                    devices = listOf(first, second),
                    ranking = PhoneRanking(true, null, phoneConnected = false, switchable = false, attempt = null),
                    actions =
                        PhoneListActions(
                            onMove = { mac, delta -> moves += mac to delta },
                            onConnect = {},
                            onRemove = {},
                            onSettings = { null },
                        ),
                )
            }
        }

    @Test
    fun the_first_phone_cannot_go_up_nor_the_last_down_and_each_arrow_is_a_button() {
        show()
        compose.onNodeWithContentDescription("Move First iPhone up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move Second iPhone down").assertIsNotEnabled()
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        compose.onNodeWithContentDescription("Move First iPhone down").assertIsEnabled().assert(button)
        compose.onNodeWithContentDescription("Move Second iPhone up").assertIsEnabled().assert(button)
        compose.onNodeWithContentDescription("Move Second iPhone up").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(second.btMac to -1), moves)
    }
}
