package com.carlink.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        listOf("Connect Adapter", "Searching for adapter...", "Adapter found — opening...", "Connecting to adapter...", "Waiting for adapter...")
            .forEach { assertEquals(it, LoadingStep.ADAPTER, step(it)) }
    }

    @Test
    fun replayedBluetoothAuthPhaseAtBootStaysOnTheFirstStep() {
        // The box re-emits its last BT phase on SUBSCRIBE; at boot that is "Authenticated" with no
        // phone anywhere near (truck 2026-10-19, box health still at rootfs-ok).
        listOf("Authenticating...", "Authenticated").forEach { assertEquals(it, LoadingStep.ADAPTER, step(it)) }
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
        listOf("Phone connected", "Starting projection...", "Re-establishing session...")
            .forEach { assertEquals(it, LoadingStep.START, step(it)) }
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
