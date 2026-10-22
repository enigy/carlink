package com.carlink.ui.settings

import com.carlink.navigation.LaneActivity
import org.junit.Assert.assertEquals
import org.junit.Test

/** The line under Settings › Cluster › Lane guidance: the most specific state wins. */
class LaneGuidanceStatusTest {
    private fun status(
        cfg: LaneGuidanceConfig,
        asked: Boolean = false,
        seen: LaneActivity.Seen? = null,
    ) = laneGuidanceStatus(cfg, asked, seen) { "8:14" }

    @Test
    fun offWhenNotAsked() {
        assertEquals("Lanes are not asked for, so the iPhone sends none.", status(LaneGuidanceConfig(ask = false)))
    }

    @Test
    fun askingWaitsForTheNextConnection() {
        assertEquals("Lanes are asked for at the next connection. Reset connection applies it now.", status(LaneGuidanceConfig(ask = true)))
        assertEquals("Lanes asked for at this connection. None received yet.", status(LaneGuidanceConfig(ask = true), asked = true))
    }

    @Test
    fun receivedLanesAreCounted() {
        assertEquals(
            "Last lanes from the iPhone: 4 lanes, 2 to be in, at 8:14.",
            status(LaneGuidanceConfig(ask = true), asked = true, seen = LaneActivity.Seen(4, 2, 1L)),
        )
    }

    @Test
    fun aRefusalSaysWhy() {
        assertEquals(
            "The iPhone refused lanes at 8:14, so asking turned off. Turn it on to try again.",
            status(LaneGuidanceConfig(ask = false, refusedAtMs = 5L)),
        )
    }

    @Test
    fun theLaneColorIsNotASwitch() {
        // Picking a color alone does not turn lane guidance on for the Cluster summary.
        assertEquals(false, LaneGuidanceConfig(ask = false, strip = false, laneColor = InlayColor.AMBER).anyOn)
        assertEquals(true, LaneGuidanceConfig().anyOn)
        assertEquals(InlayColor.AMBER.argb, LaneGuidanceConfig(laneColor = InlayColor.AMBER).laneArgb)
        assertEquals(0xFFFFFFFF.toInt(), LaneGuidanceConfig().laneArgb)
    }
}
