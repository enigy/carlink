package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MGMT_INFO's `wifi_link` object as the adapter sends it (the plan's contract item 5), the malformed and the missing too. */
class WifiLinkInfoTest {
    private fun info(wifiLink: String) = """{"name":"CarLink-0000","bt":"up","wifi_link":$wifiLink}"""

    @Test
    fun `each state the contract names, with both channels`() {
        assertEquals(WifiLinkInfo(WifiLinkInfo.State.OFF, 0, 36), WifiLinkInfo.parse(info("""{"state":"off","channel":0,"ap_channel":36}""")))
        assertEquals(WifiLinkInfo.State.JOINING, WifiLinkInfo.parse(info("""{"state":"joining","channel":0,"ap_channel":36}"""))?.state)
        assertEquals(WifiLinkInfo.State.JOINED, WifiLinkInfo.parse(info("""{"state":"joined","channel":36,"ap_channel":36}"""))?.state)
        assertEquals(WifiLinkInfo(WifiLinkInfo.State.LINKED, 149, 36), WifiLinkInfo.parse(info("""{"state":"linked","channel":149,"ap_channel":36}""")))
        assertEquals(WifiLinkInfo.State.UNSUPPORTED, WifiLinkInfo.parse(info("""{"state":"unsupported","channel":0,"ap_channel":36}"""))?.state)
    }

    @Test
    fun `channels differ only when both are known and not the same`() {
        assertTrue(WifiLinkInfo(WifiLinkInfo.State.JOINED, 149, 36).channelsDiffer)
        assertFalse(WifiLinkInfo(WifiLinkInfo.State.JOINED, 36, 36).channelsDiffer)
        assertFalse(WifiLinkInfo(WifiLinkInfo.State.JOINING, 0, 36).channelsDiffer)
        assertFalse(WifiLinkInfo(WifiLinkInfo.State.JOINED, 149, 0).channelsDiffer)
    }

    @Test
    fun `an adapter without the Wi-Fi link, and text that isn't JSON, give nothing`() {
        assertNull(WifiLinkInfo.parse("""{"name":"CarLink-0000","bt":"up"}"""))
        assertNull(WifiLinkInfo.parse(info("\"linked\"")))
        assertNull(WifiLinkInfo.parse("not json"))
        assertNull(WifiLinkInfo.parse(""))
    }

    @Test
    fun `a state this Carlink doesn't know, and bad channels, read as unknown and 0`() {
        val i = WifiLinkInfo.parse(info("""{"state":"roaming","channel":"x","ap_channel":-4}"""))!!
        assertEquals(WifiLinkInfo.State.UNKNOWN, i.state)
        assertEquals(0, i.channel)
        assertEquals(0, i.apChannel)
        assertEquals(WifiLinkInfo.State.UNKNOWN, WifiLinkInfo.parse(info("{}"))?.state)
    }
}
