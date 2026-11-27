package com.carlink.link

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * The AP link's own network, asked of Android explicitly (the plan's ADDENDUM 24 item 8). The adapter's Wi-Fi has no
 * internet, and the head unit may have another default network (its own modem), so Android leaves that Wi-Fi unvalidated
 * and never the default, and may let it go as unneeded. While Carlink holds this request (any Wi-Fi network, internet not
 * required) the network is needed, whatever its validation; the search binds its sockets to it ([WifiBoxFinder]) and never
 * relies on the default network. Held only while the AP link is chosen and set up: no other driver sees any of it.
 *
 * UNKNOWN until a head unit shows it: how GM's Android 12L treats a joined Wi-Fi with no internet (a "no internet"
 * notice, an auto-join that avoids it), and whether this request alone keeps it joined. Needs CHANGE_NETWORK_STATE.
 */
class AdapterNetworkRequest(
    private val connectivity: () -> ConnectivityManager?,
    private val log: (String) -> Unit,
) {
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null

    /** The networks Android has given this request, each with what was last said of its internet ([NOT_SAID] first). */
    private val given = ConcurrentHashMap<Network, Int>()

    /** The request is held. */
    val held: Boolean get() = callback != null

    /** The networks Android gives the request now; empty when none is up, or not held. */
    fun networks(): Set<Network> = given.keys.toSet()

    /** Ask Android for the network, once; a second call while held does nothing. */
    @Synchronized
    fun hold() {
        if (callback != null) return
        val cm = connectivity() ?: return
        val cb = Callback()
        val asked =
            runCatching { cm.requestNetwork(request(), cb) }
                .onFailure { log("[WIFI] Android refused Carlink's request for the adapter's Wi-Fi network: ${it.message}") }
                .isSuccess
        if (!asked) return
        callback = cb
        log("[WIFI] asking Android for a Wi-Fi network, internet not required (the adapter's own Wi-Fi)")
    }

    /** Let the network go; nothing when not held. */
    @Synchronized
    fun release() {
        val cb = callback ?: return
        callback = null
        given.clear()
        runCatching { connectivity()?.unregisterNetworkCallback(cb) }
        log("[WIFI] Carlink no longer asks Android for a Wi-Fi network")
    }

    private inner class Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (given.putIfAbsent(network, NOT_SAID) == null) log("[WIFI] Android has a Wi-Fi network for Carlink's request")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            caps: NetworkCapabilities,
        ) {
            // Said the first time and when it changes, not at every signal-strength update.
            val now = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) INTERNET else NO_INTERNET
            if (given.put(network, now) != now) {
                log("[WIFI] that Wi-Fi network ${if (now == INTERNET) "has internet" else "has no internet (the adapter's own has none)"}")
            }
        }

        override fun onLost(network: Network) {
            if (given.remove(network) != null) log("[WIFI] the Wi-Fi network Carlink asked for is gone")
        }
    }

    companion object {
        private const val NOT_SAID = 0
        private const val NO_INTERNET = 1
        private const val INTERNET = 2

        /** Any Wi-Fi network, internet not required: the adapter's own network has none. */
        fun request(): NetworkRequest =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
    }
}
