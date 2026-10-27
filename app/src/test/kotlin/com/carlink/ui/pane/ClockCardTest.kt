package com.carlink.ui.pane

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.carlink.car.VehicleReadings
import com.carlink.car.VehicleState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The clock card's strip of the truck's readings (prototype B), at the pane's 30 % size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClockCardTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = VehicleReadings.clear()

    private fun show() =
        compose.setContent {
            MaterialTheme { Box(modifier = Modifier.size(528.dp, 680.dp)) { ClockCard(modifier = Modifier.fillMaxSize()) } }
        }

    @Test
    fun no_readings_no_strip() {
        show()
        compose.onNodeWithText("Speed").assertDoesNotExist()
        compose.onNodeWithText("Outside").assertDoesNotExist()
    }

    @Test
    fun speed_and_outside_side_by_side_in_the_truck_s_units() {
        // The 10-26 drive: 8.59 m/s (19 mph), 16.5 °C (62 °F), the truck set to mph.
        VehicleReadings.update { VehicleState(speedMps = 8.59f, outsideC = 16.5f, speedUnit = VehicleState.MPH) }
        show()
        compose.onNodeWithText("19").assertIsDisplayed()
        compose.onNodeWithText("mph").assertIsDisplayed()
        compose.onNodeWithText("62°").assertIsDisplayed()
        compose.onNodeWithText("Outside").assertIsDisplayed()
    }

    @Test
    fun a_reading_the_car_does_not_give_is_left_out() {
        VehicleReadings.update { VehicleState(outsideC = 16.5f, speedUnit = VehicleState.MPH) }
        show()
        compose.onNodeWithText("62°").assertIsDisplayed()
        compose.onNodeWithText("Speed").assertDoesNotExist()
    }
}
