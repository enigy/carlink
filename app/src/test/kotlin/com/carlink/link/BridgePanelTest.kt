package com.carlink.link

import com.carlink.link.DirectUsb.GRANTED
import com.carlink.link.DirectUsb.IDLE
import com.carlink.link.DirectUsb.REFUSED
import com.carlink.link.DirectUsb.STOCK
import com.carlink.link.DirectUsb.WAITING
import org.junit.Assert.assertEquals
import org.junit.Test

/** When the connecting screen asks for the USB bridge, for each choice and each state of Carlink's own USB attempt. */
class BridgePanelTest {
    private fun shown(link: AdapterLink) = DirectUsb.entries.filter { BridgePanel.shows(installed = false, link = link, direct = it) }

    @Test
    fun `USB (bridge app) asks at once, as before, except while Android is asked or has given the adapter`() {
        assertEquals(listOf(IDLE, REFUSED, STOCK), shown(AdapterLink.USB_BRIDGE))
    }

    @Test
    fun `USB (direct) asks only for a stock adapter's conversion`() {
        assertEquals(listOf(STOCK), shown(AdapterLink.USB_DIRECT))
    }

    @Test
    fun `Wi-Fi never asks, and an installed bridge is never asked for`() {
        assertEquals(emptyList<DirectUsb>(), shown(AdapterLink.WIFI))
        for (link in AdapterLink.entries) {
            assertEquals(false, DirectUsb.entries.any { BridgePanel.shows(installed = true, link = link, direct = it) })
        }
        assertEquals(false, BridgePanel.shows(installed = false, link = AdapterLink.USB_BRIDGE, direct = WAITING))
        assertEquals(false, BridgePanel.shows(installed = false, link = AdapterLink.USB_BRIDGE, direct = GRANTED))
    }
}
