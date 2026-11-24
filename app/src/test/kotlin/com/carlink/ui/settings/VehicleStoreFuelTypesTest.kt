package com.carlink.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The car's reported fuel types, kept across starts, and updated when the car reports others. */
@RunWith(RobolectricTestRunner::class)
class VehicleStoreFuelTypesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a report is kept across a restart, a changed one replaces it, an empty one keeps the last`() {
        assertNull(VehicleStore(context).reportedFuelTypes.value)
        VehicleStore(context).keepReportedFuelTypes(listOf(1, 6))
        assertEquals(listOf(1, 6), VehicleStore(context).reportedFuelTypes.value)
        VehicleStore(context).keepReportedFuelTypes(listOf(3))
        assertEquals(listOf(3), VehicleStore(context).reportedFuelTypes.value)
        VehicleStore(context).keepReportedFuelTypes(emptyList())
        assertEquals(listOf(3), VehicleStore(context).reportedFuelTypes.value)
    }
}
