package com.carlink

import com.carlink.DisplayOff.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** The display-off wait's end: the session ends only with the display still off, parked and no call (Scott, 5 Nov). */
class DisplayOffTest {
    @Test
    fun `off, parked and no call ends the session`() {
        assertEquals(Decision.END_SESSION, DisplayOff.atGraceEnd(displayOn = false, driving = false, callActive = false))
    }

    @Test
    fun `a blip that ended, a moving car or a call keeps it`() {
        // Carlink2's truck logs, 9 Oct: the display went off for 38 s while streaming mid-drive.
        assertEquals(Decision.DISPLAY_ON, DisplayOff.atGraceEnd(displayOn = true, driving = false, callActive = false))
        assertEquals(Decision.KEEP_DRIVING, DisplayOff.atGraceEnd(displayOn = false, driving = true, callActive = false))
        assertEquals(Decision.KEEP_CALL, DisplayOff.atGraceEnd(displayOn = false, driving = false, callActive = true))
    }

    @Test
    fun `the wait is about half a minute`() {
        assertEquals(30_000L, DisplayOff.GRACE_MS)
        assertEquals(2_500L, DisplayOff.WAKE_SETTLE_MS)
    }
}
