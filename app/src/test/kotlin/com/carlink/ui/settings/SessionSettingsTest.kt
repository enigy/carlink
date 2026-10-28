package com.carlink.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The settings only a new connection applies, and what leaving Settings asks about (owner, 10-28). */
class SessionSettingsTest {
    private val built = SessionSettings(DisplayMode.DEFAULT, LayoutConfig(), DriveSide.LEFT, askLanes = false)
    private val rightSeat = built.copy(driveSide = DriveSide.RIGHT)
    private val split = built.copy(layout = LayoutConfig(PaneLayout.MEDIA_RIGHT))

    @Test
    fun `differences are named as Settings names them, in Settings order`() {
        val all = SessionSettings(DisplayMode.entries.first { it != DisplayMode.DEFAULT }, LayoutConfig(PaneLayout.MEDIA_LEFT), DriveSide.RIGHT, askLanes = true)
        assertEquals(
            listOf(SessionSettings.DISPLAY_MODE, SessionSettings.LAYOUT, SessionSettings.DRIVER_SEAT, SessionSettings.ASK_LANES),
            built.differences(all),
        )
        assertEquals(emptyList<String>(), built.differences(built))
    }

    @Test
    fun `a change on this visit that the session lacks is asked about`() {
        assertEquals(listOf(SessionSettings.DRIVER_SEAT), SessionSettings.toAsk(built, opened = built, saved = rightSeat))
    }

    @Test
    fun `no session means nothing to ask`() {
        assertEquals(emptyList<String>(), SessionSettings.toAsk(null, opened = built, saved = rightSeat))
    }

    @Test
    fun `a change put back before leaving is not asked about`() {
        assertEquals(emptyList<String>(), SessionSettings.toAsk(built, opened = built, saved = built))
    }

    @Test
    fun `a change already put off on an earlier visit is not asked again`() {
        assertEquals(emptyList<String>(), SessionSettings.toAsk(built, opened = rightSeat, saved = rightSeat))
    }

    @Test
    fun `a new change asks about everything the reset would bring, the earlier one too`() {
        val both = rightSeat.copy(layout = split.layout)
        assertEquals(listOf(SessionSettings.LAYOUT, SessionSettings.DRIVER_SEAT), SessionSettings.toAsk(built, opened = rightSeat, saved = both))
    }

    @Test
    fun `a change that matches the session already is not asked about`() {
        // The session was built with the right seat; the driver set it back to the left and then right again.
        assertEquals(emptyList<String>(), SessionSettings.toAsk(rightSeat, opened = built, saved = rightSeat))
    }
}
