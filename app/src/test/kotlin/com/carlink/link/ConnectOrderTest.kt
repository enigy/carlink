package com.carlink.link

import com.carlink.link.ConnectOrder.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which ways a start tries, and in what order, for each choice and whether the USB bridge is installed. */
class ConnectOrderTest {
    private val usb = WifiLinkSettings()
    private val wifiNotSetUp = WifiLinkSettings(link = AdapterLink.WIFI)
    private val wifi = WifiLinkSettings(link = AdapterLink.WIFI, ssid = "MyCar", keyStored = true)
    private val usbSetUp = wifi.copy(link = AdapterLink.USB_BRIDGE)
    private val wifiKeyLost = wifi.copy(keyUnreadable = true)

    private val direct = WifiLinkSettings(link = AdapterLink.USB_DIRECT)

    @Test
    fun `USB bridge is the bridge, as always, and Android asked directly when there is no bridge`() {
        assertEquals(listOf(Route.USB_BRIDGE), ConnectOrder.routes(usb, bridgeInstalled = true))
        assertEquals(listOf(Route.USB_DIRECT), ConnectOrder.routes(usb, bridgeInstalled = false))
        assertEquals(listOf(Route.USB_BRIDGE), ConnectOrder.routes(usbSetUp, bridgeInstalled = true))
    }

    @Test
    fun `USB direct is always Android asked directly`() {
        assertEquals(listOf(Route.USB_DIRECT), ConnectOrder.routes(direct, bridgeInstalled = true))
        assertEquals(listOf(Route.USB_DIRECT), ConnectOrder.routes(direct, bridgeInstalled = false))
    }

    @Test
    fun `Wi-Fi set up goes first, and the bridge only when it is installed`() {
        assertEquals(listOf(Route.WIFI, Route.USB_BRIDGE), ConnectOrder.routes(wifi, bridgeInstalled = true))
        assertEquals(listOf(Route.WIFI), ConnectOrder.routes(wifi, bridgeInstalled = false))
    }

    @Test
    fun `Wi-Fi not set up, or its key lost, takes a USB session to set it up through`() {
        assertEquals(listOf(Route.USB_BRIDGE), ConnectOrder.routes(wifiNotSetUp, bridgeInstalled = true))
        assertEquals(listOf(Route.USB_DIRECT), ConnectOrder.routes(wifiNotSetUp, bridgeInstalled = false))
        assertEquals(listOf(Route.USB_DIRECT), ConnectOrder.routes(wifiKeyLost, bridgeInstalled = false))
    }

    @Test
    fun `only USB bridge ever asks for the bridge`() {
        assertEquals(true, usb.bridgeNeeded)
        assertEquals(false, direct.bridgeNeeded)
        assertEquals(false, wifiNotSetUp.bridgeNeeded)
        assertEquals(listOf(AdapterLink.WIFI, AdapterLink.USB_DIRECT, AdapterLink.USB_BRIDGE), AdapterLink.entries)
        assertEquals("usb", AdapterLink.DEFAULT.code)
    }
}
