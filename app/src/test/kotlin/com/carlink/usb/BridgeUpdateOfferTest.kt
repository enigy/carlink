package com.carlink.usb

import com.enigy.carlink2.bridge.BridgeContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which way Settings offers the bridge this Carlink pins, against the installed one. */
class BridgeUpdateOfferTest {
    private val pin = PinnedBridge("1.0.9", 10, "https://github.com/enigy/carlink/releases/download/v224/carlink_bridge-1.0.9.apk", 2_800_000, "ab".repeat(32))

    @Test
    fun `an older bridge of API 7 or later updates itself`() {
        assertEquals(BridgeUpdateOffer.SelfUpdate(pin), BridgeUpdateOffer.of(9, BridgeContract.UPDATE_API_VERSION, pin))
    }

    @Test
    fun `an older bridge before API 7 takes the manual route`() {
        assertEquals(BridgeUpdateOffer.Manual(pin), BridgeUpdateOffer.of(9, 6, pin))
        assertEquals("an unknown API is an old bridge", BridgeUpdateOffer.Manual(pin), BridgeUpdateOffer.of(9, -1, pin))
    }

    @Test
    fun `the same or a newer bridge, none installed, or no pin offers nothing`() {
        assertEquals(BridgeUpdateOffer.None, BridgeUpdateOffer.of(10, 7, pin))
        assertEquals(BridgeUpdateOffer.None, BridgeUpdateOffer.of(11, 7, pin))
        assertEquals(BridgeUpdateOffer.None, BridgeUpdateOffer.of(null, 7, pin))
        assertEquals(BridgeUpdateOffer.None, BridgeUpdateOffer.of(9, 7, null))
    }

    @Test
    fun `the request carries the pin's five fields, and the manual route names the release and file`() {
        val o = JSONObject(pin.json())
        assertEquals("1.0.9", o.getString(BridgeContract.UPDATE_VERSION))
        assertEquals(10L, o.getLong(BridgeContract.UPDATE_VERSION_CODE))
        assertEquals(pin.url, o.getString(BridgeContract.UPDATE_URL))
        assertEquals(2_800_000L, o.getLong(BridgeContract.UPDATE_SIZE))
        assertEquals(pin.sha256, o.getString(BridgeContract.UPDATE_SHA256))
        assertEquals("github.com/enigy/carlink/releases/tag/v224", pin.releasePage)
        assertEquals("carlink_bridge-1.0.9.apk", pin.fileName)
    }
}
