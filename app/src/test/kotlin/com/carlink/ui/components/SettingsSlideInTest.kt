package com.carlink.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The settings page's slide: one full width off the left edge at 0, in place at 1. */
class SettingsSlideInTest {
    @Test
    fun startsOneWidthToTheLeftAndEndsInPlace() {
        assertEquals(-1f, SettingsSlideIn.offsetFraction(0f), 0f)
        assertEquals(-0.5f, SettingsSlideIn.offsetFraction(0.5f), 0f)
        assertEquals(0f, SettingsSlideIn.offsetFraction(1f), 0f)
    }

    @Test
    fun easingOvershootNeverPushesThePagePastItsEnds() {
        assertEquals(-1f, SettingsSlideIn.offsetFraction(-0.2f), 0f)
        assertEquals(0f, SettingsSlideIn.offsetFraction(1.1f), 0f)
    }
}
