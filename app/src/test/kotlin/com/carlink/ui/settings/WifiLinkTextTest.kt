package com.carlink.ui.settings

import com.carlink.link.AdapterLink
import com.carlink.link.ConnectOrder
import com.carlink.link.SetupOverNetwork
import com.carlink.link.WifiLinkSettings
import com.carlink.link.WifiLinkSetup
import com.carlink.ocbm.WifiLinkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Adapter connection's words: how Carlink reaches the adapter, what stands in a send's way, the channel line. */
class WifiLinkTextTest {
    private val wifi = WifiLinkSettings(link = AdapterLink.WIFI, ssid = "MyCar", keyStored = true)

    @Test
    fun `the line under the choice says how Carlink reaches the adapter`() {
        assertEquals("Through the Carlink USB bridge app.", WifiLinkText.connectionLine(WifiLinkSettings(), null))
        assertTrue(WifiLinkText.connectionLine(WifiLinkSettings(), null, bridgeInstalled = false).contains("asks Android"))
        assertTrue(WifiLinkText.connectionLine(WifiLinkSettings(link = AdapterLink.USB_DIRECT), null).contains("itself"))
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

    @Test
    fun `an adapter whose radio can't join a hotspot says so, and Send isn't offered`() {
        val unsupported = WifiLinkInfo(WifiLinkInfo.State.UNSUPPORTED, 0, 36)
        assertTrue(WifiLinkText.infoLine(unsupported).startsWith("No Wi-Fi link on this adapter's radio"))
        assertEquals(WifiLinkSetup.Sent.NO_STATION, WifiLinkText.blocked(wifiLinkSupported = true, info = unsupported))
        assertEquals(null, WifiLinkText.blocked(wifiLinkSupported = true, info = WifiLinkInfo(WifiLinkInfo.State.JOINED, 36, 36)))
        assertTrue(WifiLinkText.readyLine(WifiLinkSetup.Sent.NO_STATION, parked = true, problem = null).contains("Use USB"))
    }

    @Test
    fun `when no set-up Wi-Fi is found, the line says a used adapter doesn't offer it, and how to reset one`() {
        for (r in listOf(SetupOverNetwork.Result.NOT_JOINED, SetupOverNetwork.Result.NO_ADAPTER)) {
            val line = WifiLinkText.setupNetworkResult(r, "MyCar")
            assertTrue(line, line.contains("used before") && line.contains("OCBM_WIFI_RESET"))
        }
        assertTrue(WifiLinkText.setupNetworkLine(SetupOverNetwork.Phase.IDLE, null, "MyCar").contains("never used"))
    }

    @Test
    fun `the set-up question names the adapter's Bluetooth name, says a list can lag, and folds the network check in`() {
        val readable = WifiLinkText.codeText("2092", "CarLink-Setup-1a2b")
        assertTrue(readable, readable.contains("\"CarLink code 2092\"") && readable.contains("Bluetooth list") && readable.contains("take a moment"))
        assertFalse("a name Carlink could read and check costs no more words", readable.contains("Wi-Fi settings"))
        val unreadable = WifiLinkText.codeText("0007", null)
        assertTrue(unreadable, unreadable.contains("\"CarLink code 0007\"") && unreadable.contains("car's Wi-Fi settings") && unreadable.contains("CarLink-Setup-"))
        assertTrue(WifiLinkText.setupNetworkLine(SetupOverNetwork.Phase.CONFIRMING, null, "MyCar").contains("Bluetooth name"))
    }

    @Test
    fun `a set-up that ends at the question says nothing was sent, and why`() {
        assertTrue(WifiLinkText.setupNetworkResult(SetupOverNetwork.Result.NO_MATCH, "MyCar").contains("another adapter or network answered"))
        for (r in listOf(SetupOverNetwork.Result.NO_MATCH, SetupOverNetwork.Result.NOT_CONFIRMED, SetupOverNetwork.Result.LINK_LOST)) {
            val line = WifiLinkText.setupNetworkResult(r, "MyCar")
            assertTrue(line, line.lowercase().contains("nothing was sent"))
        }
    }
}
