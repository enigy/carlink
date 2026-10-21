package com.carlink.ui.settings

import com.carlink.navigation.LaneActivity
import org.junit.Assert.assertEquals
import org.junit.Test

/** The line under Settings › Cluster › Lane guidance tests: the most specific state wins. */
class LaneTestStatusTest {
    private fun status(
        tests: LaneTestConfig,
        asked: Boolean = false,
        seen: LaneActivity.Seen? = null,
    ) = laneTestStatus(tests, asked, seen) { "8:14" }

    @Test
    fun offByDefault() {
        assertEquals("Lanes are not asked for, so the iPhone sends none.", status(LaneTestConfig()))
    }

    @Test
    fun askingWaitsForTheNextConnection() {
        assertEquals("Lanes are asked for at the next connection. Reset connection applies it now.", status(LaneTestConfig(ask = true)))
        assertEquals("Lanes asked for at this connection. None received yet.", status(LaneTestConfig(ask = true), asked = true))
    }

    @Test
    fun receivedLanesAreCounted() {
        assertEquals(
            "Last lanes from the iPhone: 4 lanes, 2 to be in, at 8:14.",
            status(LaneTestConfig(ask = true), asked = true, seen = LaneActivity.Seen(4, 2, 1L)),
        )
    }

    @Test
    fun aRefusalSaysWhy() {
        assertEquals(
            "The iPhone refused lanes at 8:14, so asking turned off. Turn it on to try again.",
            status(LaneTestConfig(ask = false, refusedAtMs = 5L)),
        )
    }

    @Test
    fun theCardAndTestPatternOutrankEverythingElse() {
        assertEquals("The calibration card is on the cluster in place of the turn icon.", status(LaneTestConfig(ask = true, card = true)))
        assertEquals("Test pattern: lanes on every turn. [LANES] lines go to the drive log.", status(LaneTestConfig(source = LaneSource.TEST)))
    }
}
