package com.carlink.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import com.carlink.logging.ProbeLog
import com.carlink.ocbm.OcbsSetup
import com.carlink.ocbm.RawBulkTransport
import com.carlink.ocbm.WifiBulkTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * [SetupOverNetwork.Port] on the head unit. A network the driver joined by hand (the car's Wi-Fi settings) is used when one
 * already reaches the adapter's set-up address; otherwise Android is asked for `CarLink-Setup-*` with its published
 * passphrase (WifiNetworkSpecifier, API 29+: one system dialog to pick it). UNKNOWN until a GM head unit shows it: whether
 * its Android 12L shows that dialog at all; hence the by-hand way, which the Settings line names when it fails.
 */
class SetupNetworkPort(
    context: Context,
    private val log: ProbeLog.Logger,
    private val joinMs: Long = JOIN_MS,
) : SetupOverNetwork.Port {
    private val cm: ConnectivityManager? by lazy { context.applicationContext.getSystemService(ConnectivityManager::class.java) }

    override suspend fun join(): SetupOverNetwork.Joined? {
        val c = cm ?: return null
        byHand(c)?.let {
            log.i("set-up network: one joined by hand reaches ${OcbsSetup.BOX_ADDRESS}")
            return SetupOverNetwork.Joined(it, byHand = true) {}
        }
        val spec =
            WifiNetworkSpecifier
                .Builder()
                .setSsidPattern(PatternMatcher(OcbsSetup.SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
                .setWpa2Passphrase(OcbsSetup.PASSPHRASE)
                .build()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
        val got = CompletableDeferred<Network?>()
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    got.complete(network)
                }

                override fun onUnavailable() {
                    got.complete(null)
                }
            }
        val asked = runCatching { c.requestNetwork(request, callback) }.onFailure { log.w("set-up network request refused: ${it.message}") }.isSuccess
        if (!asked) return null
        val release = { runCatching { c.unregisterNetworkCallback(callback) }.let { } }
        // A set-up cancelled while Android is asked (the pane closed, Carlink released) lets the request go too.
        val network =
            try {
                withTimeoutOrNull(joinMs) { got.await() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                release()
                throw e
            }
        if (network == null) {
            release()
            log.w("set-up network: Android joined none within ${joinMs / 1000} s")
            return null
        }
        log.i("set-up network: joined through Android's request")
        return SetupOverNetwork.Joined(network, byHand = false, release)
    }

    override fun connect(joined: SetupOverNetwork.Joined): RawBulkTransport =
        WifiBulkTransport.open(
            InetSocketAddress(InetAddress.getByName(OcbsSetup.BOX_ADDRESS), OcbsSetup.PORT),
            log,
            joined.network?.socketFactory ?: javax.net.SocketFactory.getDefault(),
        ) { i, o -> OcbsSetup.hostHandshake(i, o) }

    /** A Wi-Fi network whose own subnet holds the set-up address: the driver joined the set-up network by hand. */
    @Suppress("DEPRECATION") // allNetworks: on API 32 still the one call that lists every network at once
    private fun byHand(c: ConnectivityManager): Network? {
        val box = InetAddress.getByName(OcbsSetup.BOX_ADDRESS)
        return runCatching {
            c.allNetworks.firstOrNull { n ->
                c.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                    c.getLinkProperties(n)?.routes?.any { r -> !r.isDefaultRoute && r.matches(box) } == true
            }
        }.getOrNull()
    }

    private companion object {
        /** The driver picks the network in Android's dialog: time for that. */
        const val JOIN_MS = 90_000L
    }
}
