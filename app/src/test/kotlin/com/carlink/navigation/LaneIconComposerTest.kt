package com.carlink.navigation

import android.content.Context
import android.graphics.Color
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Maneuver
import androidx.core.graphics.drawable.IconCompat
import androidx.test.core.app.ApplicationProvider
import com.carlink.R
import com.carlink.ui.settings.InlayColor
import com.carlink.ui.settings.LaneGuidanceConfig
import com.carlink.ui.settings.LaneIconMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lane guidance turn icons: nothing changes while they are off, a 512 px bitmap when they draw. */
@RunWith(RobolectricTestRunner::class)
class LaneIconComposerTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun rightTurn(): Maneuver =
        Maneuver
            .Builder(Maneuver.TYPE_TURN_NORMAL_RIGHT)
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(ctx, R.drawable.cp_maneuver_02_right_turn)).build())
            .build()

    private val lanes = listOf(LaneHint(0, false), LaneHint(0, false), LaneHint(90, true))

    private fun bitmapSize(m: Maneuver): Pair<Int, Int> {
        val icon = m.icon!!.icon!!
        assertEquals(IconCompat.TYPE_BITMAP, icon.type)
        return icon.bitmap!!.width to icon.bitmap!!.height
    }

    @Test
    fun offOrNoLanesKeepsTheOriginalManeuver() {
        val base = rightTurn()
        assertSame(base, LaneIconComposer.maneuver(base, lanes, LaneGuidanceConfig(), ctx))
        assertSame(base, LaneIconComposer.maneuver(base, emptyList(), LaneGuidanceConfig(icon = LaneIconMode.ARROW), ctx))
    }

    @Test
    fun bothLayoutsDrawA512SquareAndKeepTheType() {
        for (mode in listOf(LaneIconMode.ARROW, LaneIconMode.LANES)) {
            val m = LaneIconComposer.maneuver(rightTurn(), lanes, LaneGuidanceConfig(icon = mode), ctx)
            assertEquals(Maneuver.TYPE_TURN_NORMAL_RIGHT, m.type)
            assertEquals(512 to 512, bitmapSize(m))
        }
    }

    @Test
    fun theSameStepReusesItsBitmap() {
        val base = rightTurn()
        val tests = LaneGuidanceConfig(icon = LaneIconMode.ARROW)
        assertSame(LaneIconComposer.maneuver(base, lanes, tests, ctx), LaneIconComposer.maneuver(base, lanes, tests, ctx))
    }

    @Test
    fun roundaboutExitsSurviveTheNewIcon() {
        val base =
            Maneuver
                .Builder(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW)
                .setRoundaboutExitNumber(3)
                .setIcon(CarIcon.Builder(IconCompat.createWithResource(ctx, R.drawable.cp_maneuver_02_right_turn)).build())
                .build()
        val m = LaneIconComposer.maneuver(base, lanes, LaneGuidanceConfig(icon = LaneIconMode.LANES), ctx)
        assertEquals(3, m.roundaboutExitNumber)
    }

    @Test
    fun aLaneWithSeveralDirectionsDrawsAndDescribesThemAll() {
        val split = listOf(LaneHint(0, false), LaneHint(90, true, others = listOf(0)))
        val m = LaneIconComposer.maneuver(rightTurn(), split, LaneGuidanceConfig(icon = LaneIconMode.LANES), ctx)
        assertEquals(512 to 512, bitmapSize(m))
        assertEquals("2 lanes: 0 90*(+0)", LaneIconComposer.describe(split))
    }

    @Test
    fun theLaneStripIsAlwaysSixToOne() {
        // GM's lane slot is about 6:1 whatever the lane count, so the lanes split the full width.
        val three = LaneIconComposer.lanesImage(lanes, Color.WHITE).icon!!
        assertEquals(960 to 160, three.bitmap!!.width to three.bitmap!!.height)
        val two = LaneIconComposer.lanesImage(listOf(LaneHint(0, true), LaneHint(90, false)), Color.WHITE).icon!!
        assertEquals(960 to 160, two.bitmap!!.width to two.bitmap!!.height)
    }

    @Test
    fun aNewLaneColorDrawsANewStrip() {
        val white = LaneIconComposer.lanesImage(lanes, Color.WHITE)
        val amber = LaneIconComposer.lanesImage(lanes, InlayColor.AMBER.argb)
        assertNotSame(white, amber)
        assertSame(amber, LaneIconComposer.lanesImage(lanes, InlayColor.AMBER.argb))
    }
}
