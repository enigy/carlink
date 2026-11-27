package com.carlink.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.carlink.BuildConfig
import com.carlink.logging.ProbeLog
import com.carlink.ocbm.OcbwLink
import com.carlink.ocbm.WifiBulkTransport
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * [WifiBoxSearch.Port] on the head unit: the Wi-Fi networks it is on ([ConnectivityManager]), on the AP link their
 * gateways ([gateways]), the box's mDNS advert ([NsdManager], `_ocbm._tcp`), and the link itself ([WifiBulkTransport]) on
 * a socket bound to the Wi-Fi network that holds the box's address: the head unit's default network may be its own
 * cellular modem, which can't reach the adapter, and the adapter's own Wi-Fi, which has no internet, is never the default
 * (the plan's ADDENDUM 24 item 8; [AdapterNetworkRequest] keeps it up). From API 33 NSD says which network an advert came
 * on, and that one is used.
 */
class WifiBoxFinder(
    context: Context,
    private val store: WifiLinkStore,
    private val log: ProbeLog.Logger,
    private val transportTiming: WifiBulkTransport.Timing = WifiBulkTransport.Timing(),
    private val logLine: (String) -> Unit = { log.i(it) },
) : WifiBoxSearch.Port {
    private val app = context.applicationContext
    private val cm: ConnectivityManager? by lazy { app.getSystemService(ConnectivityManager::class.java) }
    private val nsd: NsdManager? by lazy { app.getSystemService(NsdManager::class.java) }

    override fun override(): WifiBoxSearch.Candidate? {
        if (!BuildConfig.DEBUG) return null
        val at = WifiLinkBench.address ?: return null
        return WifiBoxSearch.Candidate(at.hostString, at.port, "bench address", networkFor(at.hostString), remember = false)
    }

    override fun onWifi(): Boolean = wifiNetworks().isNotEmpty()

    /**
     * On the AP link, each Wi-Fi network's DHCP server, then its default route's gateway when that differs
     * ([WifiGateway]), bound to that network. On the hotspot link none: the hotspot's gateway is the phone.
     */
    override fun gateways(): List<WifiBoxSearch.Candidate> {
        if (store.current.mode != LinkMode.AP) return emptyList()
        val c = cm ?: return emptyList()
        return wifiNetworks().flatMap { n ->
            val lp = runCatching { c.getLinkProperties(n) }.getOrNull() ?: return@flatMap emptyList()
            val routes = lp.routes.filter { it.isDefaultRoute && it.hasGateway() }.mapNotNull { it.gateway }
            WifiGateway.addresses(lp.dhcpServerAddress, routes).map { WifiBoxSearch.Candidate(it, OcbwLink.PORT, "the Wi-Fi network's gateway", n) }
        }
    }

    override fun lastAddress(): WifiBoxSearch.Candidate? {
        val s = store.current
        val host = s.lastHost ?: return null
        return WifiBoxSearch.Candidate(host, s.lastPort, "last address", networkFor(host))
    }

    override fun browse(boxId: String): WifiBoxSearch.Browse? = NsdBrowse(boxId).takeIf { it.start() }

    override fun link(
        candidate: WifiBoxSearch.Candidate,
        key: ByteArray,
        connectMs: Int,
    ): WifiBoxSearch.Attempt {
        val where = "${candidate.host}:${candidate.port} (${candidate.how})"
        val factory = candidate.network?.socketFactory ?: SocketFactory.getDefault()
        return try {
            val t = WifiBulkTransport.connect(InetSocketAddress(candidate.host, candidate.port), key, log, factory, transportTiming.copy(connectMs = connectMs))
            logLine("[WIFI] linked to the adapter at $where${if (candidate.network == null) " on the default network" else ""}")
            WifiBoxSearch.Attempt.Linked(t)
        } catch (e: OcbwLink.LinkException) {
            logLine("[WIFI] $where: ${e.message}")
            WifiBoxSearch.Attempt.Failed(e.message.orEmpty(), keyRefused = e.keyRefused)
        } catch (e: IOException) {
            logLine("[WIFI] $where: ${e.javaClass.simpleName}: ${e.message}")
            WifiBoxSearch.Attempt.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun log(line: String) = logLine(line)

    /** The Wi-Fi networks the head unit is on now. */
    @Suppress("DEPRECATION") // allNetworks: on API 32 still the one call that lists every network at once
    private fun wifiNetworks(): List<Network> {
        val c = cm ?: return emptyList()
        return runCatching {
            c.allNetworks.filter { c.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrDefault(emptyList())
    }

    /** The Wi-Fi network whose own subnet holds [host]; else null, and the socket takes the default network. */
    private fun networkFor(host: String): Network? {
        val c = cm ?: return null
        // An address literal only (the search never has a name), so this never waits on DNS.
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        return wifiNetworks().firstOrNull { n ->
            c.getLinkProperties(n)?.routes?.any { r -> !r.isDefaultRoute && r.matches(addr) } == true
        }
    }

    /**
     * One browse for `_ocbm._tcp`, each advert resolved as it is found (one at a time: before API 34 NSD resolves one at a
     * time), and only those whose TXT `id` is [boxId] handed on. Another adapter on the same hotspot is logged and passed
     * over.
     */
    private inner class NsdBrowse(
        private val boxId: String,
    ) : WifiBoxSearch.Browse {
        private val found = LinkedBlockingQueue<NsdServiceInfo>()

        @Volatile private var failed = false

        private val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = logLine("[WIFI] looking for $serviceType adverts")

                override fun onServiceFound(info: NsdServiceInfo) {
                    found.add(info)
                }

                override fun onServiceLost(info: NsdServiceInfo) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    failed = true
                    logLine("[WIFI] mDNS discovery didn't start (error $errorCode)")
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) = Unit
            }

        fun start(): Boolean {
            val n = nsd ?: return false
            return runCatching { n.discoverServices(OcbwLink.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { logLine("[WIFI] mDNS discovery refused: ${it.message}") }
                .isSuccess
        }

        override fun next(waitMs: Long): WifiBoxSearch.Candidate? {
            if (failed) {
                // Discovery never started: nothing will come, but the caller's last address still gets its retries.
                Thread.sleep(waitMs)
                return null
            }
            val deadline = System.nanoTime() + waitMs * NANOS_PER_MS
            while (true) {
                val left = (deadline - System.nanoTime()) / NANOS_PER_MS
                if (left <= 0) return null
                val info = found.poll(left, TimeUnit.MILLISECONDS) ?: return null
                ours(info)?.let { return it }
            }
        }

        override fun close() {
            runCatching { nsd?.stopServiceDiscovery(listener) }
        }

        /** [info] resolved, when its advert carries this key's id; null for another adapter or a failed resolve. */
        @Suppress("DEPRECATION") // host: the API 32 way; getHostAddresses is API 34
        private fun ours(info: NsdServiceInfo): WifiBoxSearch.Candidate? {
            val r = resolve(info) ?: return null
            val host = r.host?.hostAddress ?: return null
            if (!WifiBoxSearch.isOurs(r.attributes, boxId)) {
                logLine("[WIFI] ${r.serviceName} at $host is another adapter, passed over")
                return null
            }
            val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) r.network else null
            return WifiBoxSearch.Candidate(host, r.port, "mDNS ${r.serviceName}", network ?: networkFor(host))
        }

        @Suppress("DEPRECATION") // resolveService: the API 32 way; the API 34 callbacks don't exist there
        private fun resolve(info: NsdServiceInfo): NsdServiceInfo? {
            val n = nsd ?: return null
            val done = CountDownLatch(1)
            var out: NsdServiceInfo? = null
            val listener =
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        logLine("[WIFI] couldn't resolve ${serviceInfo.serviceName} (error $errorCode)")
                        done.countDown()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        out = serviceInfo
                        done.countDown()
                    }
                }
            val started = runCatching { n.resolveService(info, listener) }.isSuccess
            if (!started) return null
            if (!done.await(RESOLVE_MS, TimeUnit.MILLISECONDS)) stopResolving(n, listener)
            return out
        }
    }

    /** A resolve that timed out lets its listener go: API 34 can stop it; before that NSD ends it on its own timeout. */
    private fun stopResolving(
        n: NsdManager,
        listener: NsdManager.ResolveListener,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) runCatching { n.stopServiceResolution(listener) }
    }

    private companion object {
        const val RESOLVE_MS = 3_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}

/**
 * The debug build's bench address for the adapter (BenchReceiver `wifi_addr`): the bench emulator reaches the adapter
 * through the Mac, where no mDNS crosses. Read only in debug builds ([WifiBoxFinder.override]); for this process only.
 */
object WifiLinkBench {
    @Volatile var address: InetSocketAddress? = null
}
