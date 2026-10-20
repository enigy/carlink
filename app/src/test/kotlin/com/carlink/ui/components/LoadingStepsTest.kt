package com.carlink.ui.components

import com.carlink.ocbm.Ocbm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The connecting screen's three steps, driven by Carlink3's (OCBM) status vocabulary. */
class LoadingStepsTest {
    private fun step(
        text: String,
        phoneConnected: Boolean = false,
    ) = LoadingSteps.classify(text, phoneConnected).step

    private fun action(text: String) = LoadingSteps.classify(text, false).action

    @Test
    fun adapterPhaseMessagesStayOnTheFirstStep() {
        listOf(
            "Connect Adapter",
            "Searching for adapter...",
            "Adapter found — opening...",
            "Connecting to adapter...",
            "Starting projection...",
            "Re-establishing session...",
            "Waiting for adapter...",
        ).forEach { assertEquals(it, LoadingStep.ADAPTER, step(it)) }
    }

    @Test
    fun replayedBluetoothAuthPhaseIsNotTheCarPlayStep() {
        // The box re-emits its last BT phase on SUBSCRIBE; at boot that is "Authenticated" with no
        // phone anywhere near (truck 2026-10-19). It arrives after the adapter is up: phone step.
        listOf("Authenticating...", "Authenticated").forEach { assertEquals(it, LoadingStep.PHONE, step(it)) }
    }

    @Test
    fun aLaunchOnlyEverMovesForward() {
        // The order CarlinkManager sets these on a cold start (truck 2026-10-20 trail), with the
        // box's replayed BT phase landing right after the SUBSCRIBE.
        val launch =
            listOf(
                "Searching for adapter...",
                "Adapter found — opening...",
                "Connecting to adapter...",
                "Starting projection...",
                "Waiting for phone...",
                "Authenticated",
                "Phone found — linking...",
                "Authenticating...",
                "Phone identified",
                "Phone connected",
            )
        val steps = launch.map { step(it) }
        steps.zipWithNext().forEachIndexed { i, (a, b) -> assertTrue("${launch[i]} -> ${launch[i + 1]}", b >= a) }
        assertEquals(LoadingStep.START, steps.last())
    }

    @Test
    fun theAdapterClaimingAProjectionStartsTheLastStep() {
        // WIRELESS_CP lands right after the Wi-Fi handoff, while the text still says the phone step.
        assertTrue(LoadingSteps.projectionStarting(Ocbm.PM_WIRELESS_CP))
        assertTrue(LoadingSteps.projectionStarting(Ocbm.PM_WIRED_CP))
        assertFalse(LoadingSteps.projectionStarting(Ocbm.PM_NONE))
        val handedOff = LoadingSteps.projectionStarting(Ocbm.PM_WIRELESS_CP)
        assertEquals(LoadingStep.START, step("Handing off to Wi-Fi...", phoneConnected = handedOff))
        assertEquals(LoadingStep.PHONE, step("Handing off to Wi-Fi...", phoneConnected = LoadingSteps.projectionStarting(Ocbm.PM_NONE)))
    }

    @Test
    fun androidAutoModesNameAndroidAuto() {
        assertTrue(LoadingSteps.isAndroidAuto(Ocbm.PM_WIRED_AA))
        assertFalse(LoadingSteps.isAndroidAuto(Ocbm.PM_WIRELESS_CP))
    }

    @Test
    fun phonePhaseMessagesAreTheSecondStep() {
        listOf(
            "Waiting for phone...",
            "Phone found — linking...",
            "Phone identified",
            "Identifying...",
            "Handing off to Wi-Fi...",
            "Pairing...",
            "Pairing code: 123456",
        ).forEach { assertEquals(it, LoadingStep.PHONE, step(it)) }
    }

    @Test
    fun sessionStartMessagesAndAConnectedPhoneAreTheThirdStep() {
        assertEquals(LoadingStep.START, step("Phone connected"))
        assertEquals(LoadingStep.START, step("Waiting for phone...", phoneConnected = true))
    }

    @Test
    fun onlyMessagesThatNeedTheDriverComeThroughAsTheActionLine() {
        assertEquals("Pairing code: 123456", action("Pairing code: 123456"))
        assertEquals("Adapter not responding — reboot adapter", action("Adapter not responding — reboot adapter"))
        assertEquals("Adapter rebooting (~50 s)...", action("Adapter rebooting (~50 s)..."))
        assertEquals("USB permission denied", action("USB permission denied"))
        assertNull(action("Waiting for phone..."))
        assertNull(action("Reconnecting (2/5)..."))
    }

    @Test
    fun labelsAreCarlink2sThreeStepsWithTheProjectionNamed() {
        assertEquals("Waking the adapter", LoadingSteps.label(LoadingStep.ADAPTER, androidAuto = false))
        assertEquals("Finding your phone", LoadingSteps.label(LoadingStep.PHONE, androidAuto = false))
        assertEquals("Starting CarPlay", LoadingSteps.label(LoadingStep.START, androidAuto = false))
        assertEquals("Starting Android Auto", LoadingSteps.label(LoadingStep.START, androidAuto = true))
    }
}
