package com.carlink.ui.settings

import com.carlink.link.AdapterLink
import com.carlink.link.ApBand
import com.carlink.link.ConnectOrder
import com.carlink.link.LinkMode
import com.carlink.link.SetupOverNetwork
import com.carlink.link.WifiBoxSearch
import com.carlink.link.WifiLinkSettings
import com.carlink.link.WifiLinkSetup
import com.carlink.ocbm.WifiLinkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AP link's words (ADDENDUM 24): the set-up, the band, the network to join, and what the adapter says. */
class ApLinkTextTest {
    private val wifi = WifiLinkSettings(link = AdapterLink.WIFI, ssid = "MyCar", keyStored = true)
    private val ap = WifiLinkSettings(link = AdapterLink.WIFI, keyStored = true, mode = LinkMode.AP, apName = "CarLink-0000", apPasswordKept = true)

    /** MGMT_INFO's `wifi_link` on the AP link (ADDENDUM 24 item 9). */
    private fun apInfo(
        state: String,
        band: String?,
        channel: Int,
    ) = WifiLinkInfo.parse("""{"wifi_link":{"mode":"ap","state":"$state",${band?.let { "\"ap_band\":\"$it\"," }.orEmpty()}"ap_channel":$channel}}""")!!

    @Test
    fun `the AP link says plainly that the head unit has no internet over the adapter's network, and how to join it`() {
        assertEquals("The head unit has no internet over this network.", ApLinkText.NO_INTERNET)
        assertTrue(ApLinkText.JOIN_STEPS.first().contains("car's Wi-Fi settings"))
        assertTrue(ApLinkText.JOIN_STEPS.last().contains("finds the adapter"))
        assertTrue(ApLinkText.sentLine(WifiLinkSetup.Sent.SENT, null).contains("car's Wi-Fi settings"))
        assertTrue(ApLinkText.setupNetworkLine(SetupOverNetwork.Phase.DONE, SetupOverNetwork.Result.SENT).contains("join the adapter's Wi-Fi"))
    }

    @Test
    fun `the AP link's set-up waits for a session and parked, and a refusal by old firmware says to update it`() {
        assertEquals(WifiLinkSetup.Sent.NO_SESSION, ApLinkText.blocked(null))
        assertEquals(WifiLinkSetup.Sent.NOT_SUPPORTED, ApLinkText.blocked(false))
        assertNull(ApLinkText.blocked(true))
        assertEquals("Connect to the adapter first.", ApLinkText.readyLine(WifiLinkSetup.Sent.NO_SESSION, parked = true))
        assertEquals("While parked.", ApLinkText.readyLine(null, parked = false))
        assertTrue(ApLinkText.readyLine(null, parked = true).contains("name and password"))
        val old = WifiLinkInfo(WifiLinkInfo.State.JOINED, 36, 36)
        assertTrue(ApLinkText.sentLine(WifiLinkSetup.Sent.REFUSED, old).contains("Update it"))
        // The adapter refuses a v2 for a moment while it can't yet say its network (notes/ocbm.md): a try later may work.
        assertEquals("The adapter refused. Try again in a moment.", ApLinkText.sentLine(WifiLinkSetup.Sent.REFUSED, apInfo("up", "2.4", 6)))
        assertEquals("The adapter refused. Try again in a moment.", ApLinkText.sentLine(WifiLinkSetup.Sent.REFUSED, null))
    }

    @Test
    fun `the band line says what 2_4 GHz is for, and when the adapter runs another band, that a set-up moves it`() {
        assertTrue(ApLinkText.bandLine(ap, null).startsWith("2.4 GHz works with most head units"))
        assertTrue(ApLinkText.bandLine(ap, apInfo("up", "2.4", 6)).startsWith("2.4 GHz"))
        val picked5 = ap.copy(band = ApBand.GHZ_5)
        assertEquals(
            "The adapter's Wi-Fi is on 2.4 GHz now. Tap Set up again to move it to 5 GHz.",
            ApLinkText.bandLine(picked5, apInfo("up", "2.4", 6)),
        )
        assertTrue(ApLinkText.movesBand(picked5, apInfo("up", "2.4", 6)))
        assertFalse(ApLinkText.movesBand(ap, apInfo("up", "2.4", 6)))
        assertFalse("the adapter's band not known: nothing to ask", ApLinkText.movesBand(picked5, null))
        assertTrue(ApLinkText.bandDialogText(ApBand.GHZ_5).contains("only over USB"))
    }

    @Test
    fun `what the adapter says of its own Wi-Fi, in plain words`() {
        assertEquals("The adapter's Wi-Fi is up: 2.4 GHz, channel 6.", ApLinkText.infoLine(apInfo("up", "2.4", 6)))
        assertEquals("Carlink is connected over the adapter's Wi-Fi: 5 GHz, channel 36.", ApLinkText.infoLine(apInfo("linked", "5", 36)))
        assertEquals("The adapter's Wi-Fi is up.", ApLinkText.infoLine(apInfo("up", null, 0)))
        assertTrue(ApLinkText.infoLine(WifiLinkInfo(WifiLinkInfo.State.JOINED, 36, 36)).contains("Update it"))
        assertTrue(ApLinkText.infoLine(WifiLinkInfo(WifiLinkInfo.State.JOINED, 6, 6, WifiLinkInfo.Mode.HOTSPOT)).contains("hotspot"))
        assertEquals("The adapter has no Wi-Fi link yet.", ApLinkText.infoLine(WifiLinkInfo(WifiLinkInfo.State.OFF, 0, 36, WifiLinkInfo.Mode.OFF)))
    }

    @Test
    fun `the lines under the AP link's set-up`() {
        val lines = ApLinkText.stateLines(ap, apInfo("up", "2.4", 6), WifiBoxSearch.Why.NO_WIFI, null, bridgeInstalled = false)
        assertEquals(
            listOf(
                "The adapter's Wi-Fi is up: 2.4 GHz, channel 6.",
                "Last look: this head unit was on no Wi-Fi network. Join the adapter's Wi-Fi in the car's Wi-Fi settings.",
                WifiLinkText.BRIDGE_MISSING,
            ),
            lines,
        )
        assertTrue("linked over Wi-Fi: no last look", ApLinkText.stateLines(ap, null, WifiBoxSearch.Why.NO_ANSWER, ConnectOrder.Route.WIFI, true).isEmpty())
        assertEquals(listOf(ApLinkText.HOTSPOT_LEFT), ApLinkText.stateLines(wifi, null, null, null, true))
        assertEquals(listOf(ApLinkText.KEY_UNREADABLE), ApLinkText.stateLines(ap.copy(keyUnreadable = true), null, null, null, true))
        assertTrue(ApLinkText.missLine(WifiBoxSearch.Why.KEY_REFUSED).contains("Set up again"))
    }

    @Test
    fun `removing the AP link says where it leaves the head unit`() {
        val t = ApLinkText.removeDialogText(ap, bridgeInstalled = false)
        assertTrue(t, t.contains("no USB bridge") && t.contains("forget \"CarLink-0000\""))
        assertFalse(ApLinkText.removeDialogText(ap.copy(apName = ""), bridgeInstalled = true).contains("forget \""))
    }
}
