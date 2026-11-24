package com.carlink.ui.settings

import com.carlink.link.AdapterLink
import com.carlink.link.ConnectOrder
import com.carlink.link.WifiLinkSettings
import com.carlink.link.WifiLinkSetup
import com.carlink.ocbm.WifiLinkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Adapter connection's words: how Carlink reaches the adapter, what stands in a send's way, the channel line. */
class WifiLinkTextTest {
    private val wifi = WifiLinkSettings(link = AdapterLink.WIFI, ssid = "MyCar", keyStored = true)

    @Test
    fun `the line under the choice says how Carlink reaches the adapter`() {
        assertEquals("Through the Carlink USB bridge.", WifiLinkText.connectionLine(WifiLinkSettings(), null))
        assertEquals("Connected over the iPhone's hotspot.", WifiLinkText.connectionLine(wifi, ConnectOrder.Route.WIFI))
        assertTrue(WifiLinkText.connectionLine(wifi, ConnectOrder.Route.USB_BRIDGE).contains("next connection"))
        assertTrue(WifiLinkText.connectionLine(WifiLinkSettings(link = AdapterLink.WIFI), null).startsWith("Send"))
    }

    @Test
    fun `a send waits for a session, an adapter that has the link, parked, and a name and password it can take`() {
        assertEquals("Connect to the adapter first.", WifiLinkText.readyLine(WifiLinkSetup.Sent.NO_SESSION, parked = true, problem = null))
        assertTrue(WifiLinkText.readyLine(WifiLinkSetup.Sent.NOT_SUPPORTED, parked = true, problem = null).contains("firmware"))
        assertEquals("While parked.", WifiLinkText.readyLine(null, parked = false, problem = null))
        assertEquals("Enter the hotspot's name.", WifiLinkText.readyLine(null, parked = true, problem = "Enter the hotspot's name."))
        assertEquals("Ready to send.", WifiLinkText.readyLine(null, parked = true, problem = null))
    }

    @Test
    fun `one plain line when the hotspot and the adapter's own Wi-Fi differ, none when they match`() {
        assertNull(WifiLinkText.channelLine(WifiLinkInfo(WifiLinkInfo.State.LINKED, 36, 36)))
        assertNull(WifiLinkText.channelLine(WifiLinkInfo(WifiLinkInfo.State.JOINING, 0, 36)))
        val line = WifiLinkText.channelLine(WifiLinkInfo(WifiLinkInfo.State.LINKED, 149, 36))!!
        assertTrue(line.contains("149") && line.contains("36"))
    }

    @Test
    fun `removing without a bridge warns that Carlink can't reach the adapter then`() {
        assertTrue(WifiLinkText.removeDialogText("MyCar", bridgeInstalled = false).contains("no USB bridge"))
        assertTrue(!WifiLinkText.removeDialogText("MyCar", bridgeInstalled = true).contains("no USB bridge"))
    }
}
