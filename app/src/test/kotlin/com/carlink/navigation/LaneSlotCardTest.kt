package com.carlink.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.carlink.ui.settings.LaneTestStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The lane strip calibration card: one height, widening shapes in turn, sent as a valid step. */
@RunWith(RobolectricTestRunner::class)
class LaneSlotCardTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun size(frame: Int): Pair<Int, Int> {
        val bmp = LaneSlotCard.image(frame).icon!!.bitmap!!
        return bmp.width to bmp.height
    }

    @Test
    fun theShapesWidenAtOneHeight() {
        assertEquals(160 to 160, size(0))
        assertEquals(640 to 160, size(3))
        assertEquals(1280 to 160, size(6))
        assertEquals("4:1, 640 x 160 px", LaneSlotCard.label(3))
    }

    @Test
    fun eachShapeShowsForOneFrameInTurn() {
        val f = LaneSlotCard.FRAME_MS
        assertEquals(0, LaneSlotCard.frameAt(0))
        assertEquals(0, LaneSlotCard.frameAt(f - 1))
        assertEquals(1, LaneSlotCard.frameAt(f))
        assertEquals(6, LaneSlotCard.frameAt(6 * f))
        assertEquals(0, LaneSlotCard.frameAt(7 * f))
    }

    @Test
    fun theStepCarriesLanesWithThePicture() {
        val store = LaneTestStore.get(ctx)
        store.setSlotCard(true)
        try {
            val step = ClusterStep.primary(NavigationState(status = 1, maneuverType = 2, remainDistance = 300), ctx)
            assertNotNull(step.lanesImage)
            assertEquals(3, step.lanes.size)
        } finally {
            store.setSlotCard(false)
        }
    }
}
