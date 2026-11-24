package com.carlink.link

import com.carlink.link.ConnectOrder.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which ways a start tries, and in what order, for each choice and whether the USB bridge is installed. */
class ConnectOrderTest {
    private val usb = WifiLinkSettings()
    private val wifiNotSetUp = WifiLinkSettings(link = AdapterLink.WIFI)
    private val wifi = WifiLinkSettings(link = AdapterLink.WIFI, ssid = "MyCar", keyStored = true)
    private val usbSetUp = wifi.copy(link = AdapterLink.USB)
    private val wifiKeyLost = wifi.copy(keyUnreadable = true)

    @Test
    fun `USB chosen is the bridge, as always, set up or not`() {
        assertEquals(listOf(Route.USB_BRIDGE), ConnectOrder.routes(usb, bridgeInstalled = true))
        assertEquals(listOf(Route.USB_BRIDGE), ConnectOrder.routes(usb, bridgeInstalled = false))
        assertEquals(listOf(Route.USB_BRIDGE), ConnectOrder.routes(usbSetUp, bridgeInstalled = true))
    }

    @Test
    fun `Wi-Fi set up goes first, and the bridge only when it is installed`() {
        assertEquals(listOf(Route.WIFI, Route.USB_BRIDGE), ConnectOrder.routes(wifi, bridgeInstalled = true))
        assertEquals(listOf(Route.WIFI), ConnectOrder.routes(wifi, bridgeInstalled = false))
    }

    @Test
    fun `Wi-Fi not set up, or its key lost, uses the bridge if there is one and else nothing`() {
        assertEquals(listOf(Route.USB_BRIDGE), ConnectOrder.routes(wifiNotSetUp, bridgeInstalled = true))
        assertEquals(emptyList<Route>(), ConnectOrder.routes(wifiNotSetUp, bridgeInstalled = false))
        assertEquals(emptyList<Route>(), ConnectOrder.routes(wifiKeyLost, bridgeInstalled = false))
    }
}
