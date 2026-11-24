package com.carlink.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

    private companion object {
        const val PREFS = "wifi_link_store_test"
    }
}
