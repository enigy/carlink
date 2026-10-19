package com.carlink.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The settings page's slide: its path, and the frame log it writes to the trail. */
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

    @Test
    fun frameSummaryPointsAtTheLongestGap() {
        val ms = 1_000_000L
        // 0, 16, 33, then a 48 ms stall, then 16 ms frames again.
        val stamps = listOf(0L, 16 * ms, 33 * ms, 81 * ms, 97 * ms)
        assertEquals("frames=5 span=97ms longestGap=48.0ms@#3 late(>25ms)=1", SettingsSlideIn.frameSummary(stamps))
    }

    @Test
    fun frameSummaryCopesWithTooFewFrames() {
        assertEquals("frames=1", SettingsSlideIn.frameSummary(listOf(5L)))
        assertEquals("frames=0", SettingsSlideIn.frameSummary(emptyList()))
    }
}
