package com.carlink.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.net.InetAddress

/**
 * The AP link's network (ADDENDUM 24 items 3 and 8): the adapter found at the gateway Android reports, and the network
 * asked of Android explicitly, internet not required, held once and let go.
 */
@RunWith(RobolectricTestRunner::class)
class AdapterNetworkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val lines = ArrayList<String>()

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun `the DHCP server comes first, then a default route's gateway that differs`() {
        assertEquals(listOf("192.0.2.1"), WifiGateway.addresses(ip("192.0.2.1"), listOf(ip("192.0.2.1"))))
        assertEquals(listOf("192.0.2.1", "192.0.2.254"), WifiGateway.addresses(ip("192.0.2.1"), listOf(ip("192.0.2.254"))))
        assertEquals("no DHCP server known: the gateway alone", listOf("192.0.2.254"), WifiGateway.addresses(null, listOf(ip("192.0.2.254"))))
        assertEquals(emptyList<String>(), WifiGateway.addresses(null, emptyList()))
    }

    @Test
    fun `only an IPv4 unicast address a connect can reach is tried`() {
        for (no in listOf("0.0.0.0", "127.0.0.1", "169.254.1.1", "224.0.0.251", "255.255.255.255", "2001:db8::1")) {
            assertFalse(no, WifiGateway.usable(ip(no)))
        }
        assertTrue(WifiGateway.usable(ip("192.0.2.1")))
        assertEquals(listOf("198.51.100.1"), WifiGateway.addresses(ip("0.0.0.0"), listOf(ip("2001:db8::1"), ip("198.51.100.1"))))
    }

    @Test
    fun `the request is any Wi-Fi network, internet not required`() {
        val r = AdapterNetworkRequest.request()
        assertTrue(r.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertFalse(r.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse("never asks for a validated network", r.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    @Test
    fun `held once however often it is asked, and let go`() {
        val hold = AdapterNetworkRequest({ cm }) { lines.add(it) }
        val before = shadowOf(cm).networkCallbacks.size
        hold.hold()
        hold.hold()
        assertTrue(hold.held)
        assertEquals(before + 1, shadowOf(cm).networkCallbacks.size)
        hold.release()
        assertFalse(hold.held)
        assertEquals(before, shadowOf(cm).networkCallbacks.size)
        hold.release()
        assertEquals("one line each way, none for the repeats", 2, lines.size)
    }

    @Test
    fun `with no ConnectivityManager nothing is held and nothing breaks`() {
        val hold = AdapterNetworkRequest({ null }) { lines.add(it) }
        hold.hold()
        assertFalse(hold.held)
        hold.release()
        assertTrue(lines.isEmpty())
        assertTrue(hold.networks().isEmpty())
    }
}
