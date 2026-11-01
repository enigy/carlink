package com.carlink.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.carlink.CarlinkManager
import com.carlink.ocbm.ConnectAttempt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Phones' Connect shows the press at once, ignores more taps while it works, then says how it went (owner, 11-01). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    private val owner = CarlinkManager.DeviceInfo(btMac = "68:a7:29:00:25:10", name = "Owner's iPhone", type = "iPhone")
    private val other = CarlinkManager.DeviceInfo(btMac = "aa:bb:cc:00:6a:de", name = "Second iPhone", type = "iPhone")
    private val attempt = mutableStateOf<ConnectAttempt?>(null)
    private var presses = 0

    private val third = CarlinkManager.DeviceInfo(btMac = "11:22:33:44:55:66", name = "Third iPhone", type = "iPhone")

    private fun show(devices: List<CarlinkManager.DeviceInfo> = listOf(owner, other)) =
        compose.setContent {
            MaterialTheme {
                KnownPhones(
                    usbInUse = false,
                    devices = devices,
                    // The second iPhone projects over Wi-Fi; the box switches phones.
                    ranking = PhoneRanking(true, other.btMac, phoneConnected = true, switchable = true, attempt = attempt.value),
                    actions =
                        PhoneListActions(
                            onMove = { _, _ -> },
                            onConnect = {
                                presses++
                                attempt.value = ConnectAttempt(it.btMac.lowercase())
                            },
                            onRemove = {},
                            onSettings = { null },
                        ),
                )
            }
        }

    @Test
    fun a_press_shows_working_dots_at_once_and_ignores_more_taps() {
        show()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Connect").assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Connecting").assertExists()
        compose.onNodeWithContentDescription("Connecting").performClick()
        compose.mainClock.advanceTimeBy(100)
        assertEquals("further taps do nothing while it works", 1, presses)
    }

    @Test
    fun the_outcome_says_how_it_went() {
        attempt.value = ConnectAttempt(owner.btMac, ConnectAttempt.Outcome.NOT_REACHED)
        show()
        compose.onNodeWithText("Couldn't reach Owner's iPhone", substring = true).assertExists()
        compose.onNodeWithText("Connect").assertIsEnabled()
    }

    @Test
    fun while_one_press_works_the_other_rows_wait() {
        attempt.value = ConnectAttempt(owner.btMac)
        show(listOf(owner, other, third))
        compose.onNodeWithContentDescription("Connecting").assertExists()
        compose.onNodeWithText("Connect").assertIsNotEnabled()
    }
}
