package com.carlink.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.carlink.ocbm.ApNetwork
import com.carlink.ocbm.OcbwLink
import com.carlink.ocbm.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Wi-Fi link's settings survive a restart, and the link key is kept sealed, never in plain preferences. */
@RunWith(RobolectricTestRunner::class)
class WifiLinkStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun store(sealer: LinkKeySealer = FakeSealer()) = WifiLinkStore(context, sealer, PREFS)

    @Test
    fun `USB by default, and the choice is kept across a restart`() {
        assertEquals(AdapterLink.USB_BRIDGE, store().current.link)
        assertTrue(store().current.bridgeNeeded)
        store().setLink(AdapterLink.WIFI)
        val again = store()
        assertEquals(AdapterLink.WIFI, again.current.link)
        assertFalse("chosen but not set up", again.current.wifiActive)
        assertFalse("Wi-Fi chosen: no screen asks for the bridge", again.current.bridgeNeeded)
    }

    @Test
    fun `a set-up the adapter took is kept, its key sealed and opened again after a restart`() {
        val key = OcbwLink.random32()
        store().apply {
            setLink(AdapterLink.WIFI)
            setUp(key, "MyCar")
        }
        val again = store()
        assertTrue(again.current.wifiActive)
        assertEquals("MyCar", again.current.ssid)
        assertArrayEquals(key, again.linkKey())
        assertArrayEquals("a send again keeps the key", key, again.keyForSending())
        val kept =
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .all
                .values
                .map { it.toString() }
        assertFalse("the key is never in plain preferences", kept.any { it.contains(key.toHex(), ignoreCase = true) })
        assertEquals(1, kept.count { it.length > key.size }) // the sealed text, and nothing else of that size
    }

    @Test
    fun `the last address is remembered, and forgotten when the hotspot changes`() {
        val s = store()
        s.setUp(OcbwLink.random32(), "MyCar")
        s.rememberAddress("192.0.2.10", OcbwLink.PORT)
        assertEquals("192.0.2.10", store().current.lastHost)
        s.setUp(s.linkKey()!!, "OtherCar")
        assertNull(store().current.lastHost)
    }

    @Test
    fun `a key this head unit can no longer open reads as not set up`() {
        store().apply {
            setLink(AdapterLink.WIFI)
            setUp(OcbwLink.random32(), "MyCar")
        }
        val lost = store(FakeSealer(broken = true))
        assertNull(lost.linkKey())
        assertTrue(lost.current.keyUnreadable)
        assertFalse(lost.current.setUp)
        assertTrue(store(FakeSealer(broken = true)).current.keyUnreadable)
        assertNotNull("a new key is made to send", lost.keyForSending())
    }

    @Test
    fun `Remove forgets the key, the hotspot and the address, and goes back to USB`() {
        val s = store()
        s.setLink(AdapterLink.WIFI)
        s.setUp(OcbwLink.random32(), "MyCar")
        s.rememberAddress("192.0.2.10", OcbwLink.PORT)
        s.forget()
        val again = store()
        assertEquals(WifiLinkSettings(link = AdapterLink.USB_BRIDGE), again.current)
        assertNull(again.linkKey())
    }

    @Test
    fun `an unknown stored code reads as USB`() {
        assertEquals(AdapterLink.USB_BRIDGE, AdapterLink.fromCode("carrier pigeon"))
        assertEquals(AdapterLink.WIFI, AdapterLink.fromCode("wifi"))
    }

    @Test
    fun `a KeyStore failing for now keeps the key, and only a definite failure marks it unreadable`() {
        val sealer = FakeSealer()
        val s = WifiLinkStore(context, sealer, PREFS)
        val key = OcbwLink.random32()
        s.setUp(key, "MyCar")
        sealer.unavailable = true
        assertNull(s.linkKey())
        assertNull("no new key replaces one that can't be read just now", s.keyForSending())
        assertFalse(s.current.keyUnreadable)
        assertFalse("a seal that fails keeps nothing", s.setUp(OcbwLink.random32(), "OtherCar"))
        assertEquals("MyCar", s.current.ssid)
        sealer.unavailable = false
        assertArrayEquals("readable again on the next try", key, s.linkKey())
        assertNull(WifiLinkStore(context, FakeSealer(broken = true), PREFS).linkKey())
        assertTrue(WifiLinkStore(context, FakeSealer(), PREFS).current.keyUnreadable)
    }

    // ---- The AP link (ADDENDUM 24) ----

    private val network = ApNetwork("CarLink-0000", "example-pass")

    @Test
    fun `the AP link's set-up is kept across a restart, the key and the network's password only sealed`() {
        val key = OcbwLink.random32()
        store().apply {
            setLink(AdapterLink.WIFI)
            assertTrue(setUpAp(key, ApBand.GHZ_5, network))
        }
        val again = store()
        assertTrue(again.current.wifiActive)
        assertTrue(again.current.apLink)
        assertEquals(LinkMode.AP, again.current.mode)
        assertEquals(ApBand.GHZ_5, again.current.band)
        assertEquals("CarLink-0000", again.current.apName)
        assertTrue(again.current.apPasswordKept)
        assertEquals("example-pass", again.apPassword())
        assertArrayEquals(key, again.linkKey())
        assertEquals("the AP link names no hotspot", "", again.current.ssid)
        val kept =
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .all
                .values
                .map { it.toString() }
        assertFalse("the key is never in plain preferences", kept.any { it.contains(key.toHex(), ignoreCase = true) })
        assertFalse("nor the network's password", kept.any { it.contains("example-pass") })
        assertEquals("the two sealed texts, and nothing else of that size", 2, kept.count { it.length > key.size })
    }

    @Test
    fun `a set-up whose answer named no network holds the key, with no name or password`() {
        val s = store()
        assertTrue(s.setUpAp(OcbwLink.random32(), ApBand.GHZ_2_4, null))
        assertTrue(store().current.apLink)
        assertEquals("", store().current.apName)
        assertFalse(store().current.apPasswordKept)
        assertNull(store().apPassword())
    }

    @Test
    fun `the band is the driver's choice, 2_4 GHz until they pick, and kept`() {
        assertEquals(ApBand.GHZ_2_4, store().current.band)
        store().setBand(ApBand.GHZ_5)
        assertEquals(ApBand.GHZ_5, store().current.band)
        assertEquals(ApBand.GHZ_2_4, ApBand.fromCode("6"))
        assertEquals(null, ApBand.ofCode(null))
        assertEquals(listOf("2.4 GHz", "5 GHz"), ApBand.entries.map { it.label })
    }

    @Test
    fun `a hotspot set-up from before ADDENDUM 24 reads as the hotspot link, and the AP link's set-up replaces it`() {
        val key = OcbwLink.random32()
        // What an earlier build kept: the hotspot's name and the sealed key, and no mode.
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("ssid", "MyCar")
            .putString("link_key_sealed_v1", FakeSealer().seal(key))
            .commit()
        val legacy = store().current
        assertEquals(LinkMode.HOTSPOT, legacy.mode)
        assertTrue(legacy.setUp)
        assertFalse(legacy.apLink)
        val s = store()
        assertTrue(s.setUpAp(s.linkKey()!!, ApBand.GHZ_2_4, network))
        val now = store().current
        assertTrue(now.apLink)
        assertEquals("", now.ssid)
        assertArrayEquals("the key the adapter holds is kept", key, store().linkKey())
    }

    @Test
    fun `Remove forgets the AP link's network, password and band too`() {
        val s = store()
        s.setLink(AdapterLink.WIFI)
        s.setUpAp(OcbwLink.random32(), ApBand.GHZ_5, network)
        s.rememberAddress("192.0.2.1", OcbwLink.PORT)
        s.forget()
        val again = store()
        assertEquals(WifiLinkSettings(link = AdapterLink.USB_BRIDGE), again.current)
        assertNull(again.apPassword())
        assertTrue(
            "nothing of the link is left in the preferences",
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .all.keys == setOf("link"),
        )
    }

    @Test
    fun `a password this head unit can no longer open is not shown, and the key's state is not touched by it`() {
        store().setUpAp(OcbwLink.random32(), ApBand.GHZ_2_4, network)
        val lost = store(FakeSealer(broken = true))
        assertNull(lost.apPassword())
        assertFalse(lost.current.keyUnreadable)
    }

    @Test
    fun `a KeyStore that can't seal keeps nothing of an AP set-up`() {
        val sealer = FakeSealer()
        val s = WifiLinkStore(context, sealer, PREFS)
        sealer.unavailable = true
        assertFalse(s.setUpAp(OcbwLink.random32(), ApBand.GHZ_2_4, network))
        assertFalse(s.current.setUp)
        assertFalse(store().current.keyStored)
    }

    private companion object {
        const val PREFS = "wifi_link_store_test"
    }
}
