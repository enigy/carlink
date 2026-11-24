package com.carlink.link

import com.carlink.link.WifiAttach.Action
import com.carlink.ocbm.HelloReplay
import com.carlink.ocbm.Ocbm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2 on Carlink's side: attaching over Wi-Fi to a session the adapter already runs, and looking for it while Wi-Fi mode lasts. */
class WifiAttachTest {
    @Test
    fun `a running session over Wi-Fi is attached to, with no wait for the phone`() {
        assertEquals(Action.ATTACH, WifiAttach.onDecided(live = true, mode = Ocbm.PM_WIRELESS_CP, overWifi = true))
        assertEquals(Action.WAIT_FOR_PHONE, WifiAttach.onDecided(live = true, mode = Ocbm.PM_NONE, overWifi = true))
        assertEquals(Action.WAIT_FOR_PHONE, WifiAttach.onDecided(live = false, mode = Ocbm.PM_WIRELESS_CP, overWifi = true))
        assertEquals(Action.WAIT_FOR_PHONE, WifiAttach.onDecided(live = true, mode = null, overWifi = true))
    }

    @Test
    fun `over USB nothing changes`() {
        assertEquals(Action.AS_BEFORE, WifiAttach.onDecided(live = true, mode = Ocbm.PM_WIRELESS_CP, overWifi = false))
        assertEquals(Action.AS_BEFORE, WifiAttach.onDecided(live = true, mode = Ocbm.PM_NONE, overWifi = false))
    }

    @Test
    fun `the box's live mode right after its replay decides at once that the session is the attach's`() {
        val replay = HelloReplay()
        replay.start()
        assertEquals(HelloReplay.Verdict.Hold, replay.onProjMode(Ocbm.PM_WIRELESS_CP, replay = true))
        val v = replay.onProjMode(Ocbm.PM_WIRELESS_CP, replay = false) as HelloReplay.Verdict.Decided
        assertEquals(Action.ATTACH, WifiAttach.onDecided(v.release.live, v.release.mode, overWifi = true))
    }

    @Test
    fun `the bridge takes over only after the Wi-Fi searches have found nothing for a while`() {
        assertFalse(WifiAttach.usbFallbackDue(searchingSinceMs = 0, nowMs = 100_000))
        assertFalse(WifiAttach.usbFallbackDue(searchingSinceMs = 10_000, nowMs = 10_000 + WifiAttach.USB_FALLBACK_MS - 1))
        assertTrue(WifiAttach.usbFallbackDue(searchingSinceMs = 10_000, nowMs = 10_000 + WifiAttach.USB_FALLBACK_MS))
    }

    @Test
    fun `Carlink keeps looking only while Wi-Fi mode is on, nothing is linked and nothing stopped it`() {
        assertTrue(WifiAttach.keepLooking(wifiActive = true, disconnected = true, stopped = false))
        assertFalse(WifiAttach.keepLooking(wifiActive = false, disconnected = true, stopped = false))
        assertFalse(WifiAttach.keepLooking(wifiActive = true, disconnected = false, stopped = false))
        assertFalse(WifiAttach.keepLooking(wifiActive = true, disconnected = true, stopped = true))
    }

    @Test
    fun `a USB detach ends a session outside Wi-Fi mode, and in Wi-Fi mode only one that rides USB`() {
        assertTrue(WifiAttach.detachEndsSession(wifiChosen = false, overUsb = false)) // a USB start before its client: as before
        assertTrue(WifiAttach.detachEndsSession(wifiChosen = false, overUsb = true))
        assertTrue("Wi-Fi mode, fallen back to the bridge", WifiAttach.detachEndsSession(wifiChosen = true, overUsb = true))
        assertFalse("Wi-Fi mode, linked over Wi-Fi or still searching", WifiAttach.detachEndsSession(wifiChosen = true, overUsb = false))
    }

    @Test
    fun `in Wi-Fi mode Carlink never speaks on USB while its Wi-Fi link is healthy, and only after 45 s of finding nothing`() {
        // A link taken over Wi-Fi resets the clock to 0: USB stays shut for as long as it lasts.
        assertFalse(WifiAttach.mayTryUsb(wifiActive = true, searchingSinceMs = 0, nowMs = 1_000_000))
        assertFalse(WifiAttach.mayTryUsb(wifiActive = true, searchingSinceMs = 5_000, nowMs = 5_000 + WifiAttach.USB_FALLBACK_MS - 1))
        assertTrue(WifiAttach.mayTryUsb(wifiActive = true, searchingSinceMs = 5_000, nowMs = 5_000 + WifiAttach.USB_FALLBACK_MS))
        assertTrue("USB and USB direct users: as always", WifiAttach.mayTryUsb(wifiActive = false, searchingSinceMs = 0, nowMs = 0))
    }

    @Test
    fun `a start in Wi-Fi mode takes only Wi-Fi until USB may be spoken`() {
        val wifi = WifiLinkSettings(link = AdapterLink.WIFI, ssid = "MyCar", keyStored = true)
        val routes = ConnectOrder.routes(wifi, bridgeInstalled = true)
        val usbShut = WifiAttach.mayTryUsb(wifi.wifiActive, searchingSinceMs = 0, nowMs = 60_000)
        assertEquals(listOf(ConnectOrder.Route.WIFI), routes.filter { it == ConnectOrder.Route.WIFI || usbShut })
    }
}
