package com.carlink.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMicConfigTest {
    @Test
    fun `stored choices from before the call path list still load`() {
        assertEquals(CallMicConfig.AUTOMATIC, CallMicConfig.fromCode(null))
        assertEquals(CallMicConfig.AUTOMATIC, CallMicConfig.fromCode("automatic"))
        assertEquals(CallMicConfig.CAR_CALL_PATH, CallMicConfig.fromCode("voice_uplink"))
        assertEquals(CallMicConfig.SYSTEM, CallMicConfig.fromCode("system"))
        assertEquals(CallMicConfig.AUTOMATIC, CallMicConfig.fromCode("something_else"))
    }

    @Test
    fun `a pinned input round-trips through its code with a readable label`() {
        val usb = CallMicConfig.forDevice("USB:card=1;device=0")
        assertEquals(usb, CallMicConfig.fromCode(usb.code))
        assertEquals("USB mic", usb.label)
        assertEquals("Car call path", CallMicConfig.CAR_CALL_PATH.label)
        assertEquals("BUS:Voice_Uplink", CallMicConfig.CAR_CALL_PATH.deviceKey)
        assertEquals("Built-in mic (bottom)", CallMicConfig.labelFor("BUILTIN_MIC:bottom"))
        assertNull(CallMicConfig.AUTOMATIC.deviceKey)
        assertTrue(CallMicConfig.isCallUplink("mic_for_vc"))
    }

    @Test
    fun `options are automatic, system, then the car's inputs, keeping a missing selection`() {
        val opts = CallMicChoices.options(listOf("BUS:Voice_Uplink"), CallMicConfig.AUTOMATIC)
        assertEquals(listOf("Automatic", "System choice", "Car call path"), opts.map { it.label })
        val unplugged = CallMicConfig.forDevice("USB:card=1")
        assertEquals(unplugged, CallMicChoices.options(listOf("BUS:Voice_Uplink"), unplugged).last())
    }

    @Test
    fun `the note says what automatic picked, and only for this car software`() {
        val picked = AutoCallPath("BUS:Voice_Uplink", "build-1", "car call path carries sound (-74 dBFS on BUS(Voice_Uplink))")
        assertEquals(
            "Using the car call path: car call path carries sound (-74 dBFS on BUS(Voice_Uplink)).",
            CallMicChoices.note(CallMicConfig.AUTOMATIC, picked, "build-1", carListsInputs = true),
        )
        assertTrue(CallMicChoices.note(CallMicConfig.AUTOMATIC, picked, "build-2", carListsInputs = true).startsWith("Checks this car"))
        assertEquals(
            "Every call records from Car call path. Automatic's checks are off.",
            CallMicChoices.note(CallMicConfig.CAR_CALL_PATH, picked, "build-1", carListsInputs = true),
        )
    }
}
