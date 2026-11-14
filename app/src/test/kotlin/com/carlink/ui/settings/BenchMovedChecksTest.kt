package com.carlink.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.carlink.car.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Checks the Mac's bench made by eye, as tests (its bench hooks of 13 Nov, item 9): the engine type selector and the
 * Phones page's call-order line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BenchMovedChecksTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun with_nothing_picked_the_selector_shows_the_car_s_own_and_no_from_the_car() {
        val store = VehicleStore.get(context)
        store.setEngineChoice(EngineChoice.CAR)
        compose.setContent { MaterialTheme { EngineGroup(store, EngineChoice.CAR, VehicleState(fuelTypes = listOf(3))) } }
        compose.onNodeWithText("Diesel").assertIsSelected()
        compose.onNodeWithText("Gasoline").assertIsNotSelected()
        compose.onNodeWithText("From the car").assertDoesNotExist()
    }

    @Test
    fun a_tap_is_the_driver_s_pick() {
        val store = VehicleStore.get(context)
        store.setEngineChoice(EngineChoice.CAR)
        compose.setContent { MaterialTheme { EngineGroup(store, store.engineChoice.value, VehicleState(fuelTypes = listOf(3))) } }
        compose.onNodeWithText("Gasoline").performClick()
        assertEquals(EngineChoice.GASOLINE, store.engineChoice.value)
        // A restart reads it back by its code (EngineChoiceTest); the store is one per process, so not from here.
        store.setEngineChoice(EngineChoice.CAR)
    }

    @Test
    fun the_call_order_line_from_two_phones_with_no_fixed_wait() {
        assertEquals("one phone has no order to explain", 2, CALL_ORDER_FROM)
        compose.setContent { MaterialTheme { CallOrderLine() } }
        compose
            .onNodeWithText(
                "Phones are tried in this order. If the first phone isn’t available, the adapter waits a few seconds for it " +
                    "before trying the next phone.",
            ).assertExists()
    }
}
