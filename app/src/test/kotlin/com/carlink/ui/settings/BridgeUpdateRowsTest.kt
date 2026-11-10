package com.carlink.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.carlink.usb.BridgeUpdateOffer
import com.carlink.usb.BridgeUpdateStep
import com.carlink.usb.PinnedBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Adapter and about › USB bridge: the update row, its parked gating, its confirm, and the manual route. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BridgeUpdateRowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val pin = PinnedBridge("1.0.9", 10, "https://github.com/enigy/carlink/releases/download/v224/carlink_bridge-1.0.9.apk", 2_800_000, "ab".repeat(32))
    private val updates = mutableListOf<PinnedBridge>()
    private var filesOpened = 0

    private fun show(
        offer: BridgeUpdateOffer,
        step: BridgeUpdateStep = BridgeUpdateStep.Idle,
        driving: Boolean = false,
    ) {
        compose.setContent {
            MaterialTheme {
                Column {
                    BridgeUpdateRows(offer, step, driving, onUpdate = { updates += it }, onAllowInstalls = null, onOpenFiles = {
                        filesOpened++
                        true
                    })
                }
            }
        }
    }

    @Test
    fun `Update asks first, and only the confirm calls the bridge`() {
        show(BridgeUpdateOffer.SelfUpdate(pin))
        compose.onNodeWithText("Bridge 1.0.9 available").assertExists()
        compose.onNodeWithText("Update").assertIsEnabled().performClick()
        assertTrue("nothing before the confirm", updates.isEmpty())
        compose.onNodeWithText(BridgeUpdateText.CONFIRM_TITLE).assertExists()
        compose.onNodeWithText("Update bridge").performClick()
        assertEquals(listOf(pin), updates)
    }

    @Test
    fun `Update is off while driving, and while one runs`() {
        show(BridgeUpdateOffer.SelfUpdate(pin), driving = true)
        compose.onNodeWithText("Update").assertIsNotEnabled()
        assertFalse(BridgeUpdateText.updateEnabled(driving = false, step = BridgeUpdateStep.Downloading))
        assertTrue(BridgeUpdateText.updateEnabled(driving = false, step = BridgeUpdateStep.Failed("no network")))
    }

    @Test
    fun `each step and the outcome read in the row`() {
        show(BridgeUpdateOffer.SelfUpdate(pin), step = BridgeUpdateStep.Failed("The download from github.com failed"))
        compose.onNodeWithText("The update didn't happen: The download from github.com failed").assertExists()
        assertEquals("Downloading…", BridgeUpdateText.stepLine(BridgeUpdateStep.Downloading))
        assertEquals(
            "Confirm the update on the screen the head unit shows. If it's gone, Update comes back after 10 minutes.",
            BridgeUpdateText.stepLine(BridgeUpdateStep.Confirming),
        )
        assertEquals("Updated to bridge 1.0.9.", BridgeUpdateText.stepLine(BridgeUpdateStep.Updated("1.0.9")))
        assertNull(BridgeUpdateText.stepLine(BridgeUpdateStep.Idle))
    }

    @Test
    fun `a bridge not allowed to install apps says where to allow it`() {
        show(BridgeUpdateOffer.SelfUpdate(pin), step = BridgeUpdateStep.NotAllowed)
        compose.onNodeWithText(BridgeUpdateText.NOT_ALLOWED_NO_PAGE).assertExists()
    }

    @Test
    fun `an older bridge gets the manual route, with the file, its hash and Open Files`() {
        show(BridgeUpdateOffer.Manual(pin))
        compose.onNodeWithText("How to update").performClick()
        compose.onNodeWithText(pin.sha256, substring = true).assertExists()
        compose.onNodeWithText("carlink_bridge-1.0.9.apk", substring = true).assertExists()
        compose.onNodeWithText("Open Files").performClick()
        assertEquals(1, filesOpened)
    }

    @Test
    fun `a current bridge shows nothing to update`() {
        show(BridgeUpdateOffer.None)
        compose.onNodeWithText("Update").assertDoesNotExist()
    }
}
