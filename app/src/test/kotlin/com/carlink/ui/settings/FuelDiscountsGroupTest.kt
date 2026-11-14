package com.carlink.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.carlink.car.DrivingState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Vehicle › Fuel discounts: entered parked only (the Mac's 13 Nov review, item 2), under its own heading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FuelDiscountsGroupTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = DrivingState.setParked(false)

    private fun show() {
        val store = VehicleStore.get(ApplicationProvider.getApplicationContext())
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { FuelDiscountsGroup(store) } } }
    }

    @Test
    fun a_dialog_open_when_the_truck_moves_closes() {
        DrivingState.setParked(true)
        show()
        compose.onNodeWithText("FUEL DISCOUNTS").assertExists()
        compose.onAllNodesWithText("Set")[0].performClick()
        compose.onNodeWithText("Cents off per gallon (blank: not a member)").assertExists()
        DrivingState.setParked(false)
        compose.waitForIdle()
        compose.onNodeWithText("Cents off per gallon (blank: not a member)").assertDoesNotExist()
    }

    @Test
    fun nothing_can_be_set_while_moving() {
        DrivingState.setParked(false)
        show()
        compose.onAllNodesWithText("Set")[0].assertIsNotEnabled()
    }
}
