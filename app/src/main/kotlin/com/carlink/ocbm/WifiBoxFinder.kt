package com.carlink.ocbm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.carlink.logging.ProbeLog
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Finds the adapter on the car's hotspot: the box advertises `_ocbm._tcp` on its STA link with TXT
 * `id=`[OcbwLink.boxId], so Carlink takes the advert whose id matches its own link key and ignores any
 * other OCBM box on the same hotspot. The hotspot hands the box a different address now and then, so the
 * last one that answered is only a first guess ([WifiLinkSettings.lastHost]).
 */
class WifiBoxFinder(
    context: Context,
    private val log: ProbeLog.Logger,
) {
    data class Candidate(
        val host: String,
        val port: Int,
        /** The network the box answered on, when Android says (API 33+); else found from the routes. */
        val network: Network?,
        val how: String,
    )

    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * The socket factory for [host]: bound to the network whose own subnet holds it (a Wi-Fi network that
     * isn't the default, say, when the head unit's default is cellular), else the default. A head unit that
     * IS the hotspot reaches its clients through the tethering routes, which an unbound socket takes.
     */
    @Suppress("DEPRECATION") // allNetworks: still the one call that lists every network on API 32
    fun socketFactoryFor(
        host: String,
        network: Network?,
    ): SocketFactory {
        network?.let { return it.socketFactory }
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return SocketFactory.getDefault()
        val net =
            cm.allNetworks.firstOrNull { n ->
                cm.getLinkProperties(n)?.routes?.any { r -> !r.isDefaultRoute && r.matches(addr) } == true
            }
        return net?.socketFactory ?: SocketFactory.getDefault()
    }

    /**
     * Browse for `_ocbm._tcp` and resolve adverts until one carries [boxId], for up to [timeoutMs]; null when
     * none did. Blocking: call off the main thread. Resolves run one at a time (API < 34 allows only one).
     */
    @Suppress("DEPRECATION") // resolveService/host: the API-32 way; the API-34 callbacks don't exist there
    fun discover(
        boxId: String,
        timeoutMs: Long,
    ): Candidate? {
        val found = LinkedBlockingQueue<NsdServiceInfo>()
        val listener = collectInto(found)
        val started = runCatching { nsd.discoverServices(OcbwLink.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
        if (!started) return null
        try {
            return firstMine(found, boxId, timeoutMs)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    /** The first advert in [found] that resolves to [boxId] within [timeoutMs]; each service tried once. */
    private fun firstMine(
        found: LinkedBlockingQueue<NsdServiceInfo>,
        boxId: String,
        timeoutMs: Long,
    ): Candidate? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val tried = HashSet<String>()
        var left = timeoutMs
        while (left > 0) {
            val info = found.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (tried.add(info.serviceName)) mine(info, boxId, minOf(left, RESOLVE_TIMEOUT_MS))?.let { return it }
            left = (deadline - System.nanoTime()) / 1_000_000
        }
        return null
    }

    /** A discovery listener that queues every service it finds into [found]. */
    private fun collectInto(found: LinkedBlockingQueue<NsdServiceInfo>): NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = log.i("wifi: looking for $serviceType on the network")

            override fun onServiceFound(info: NsdServiceInfo) {
                found.add(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) = log.w("wifi: discovery didn't start (error $errorCode)")

            override fun onStopDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) = Unit
        }

    /** [info] resolved, when its advert carries [boxId]; null for another adapter or a failed resolve. */
    @Suppress("DEPRECATION") // host: the API-32 way
    private fun mine(
        info: NsdServiceInfo,
        boxId: String,
        timeoutMs: Long,
    ): Candidate? {
        val resolved = resolve(info, timeoutMs) ?: return null
        val host = resolved.host?.hostAddress ?: return null
        val id = resolved.attributes["id"]?.let { String(it, Charsets.US_ASCII) }
        if (id != boxId) {
            log.i("wifi: ${resolved.serviceName} at $host is another adapter (id $id), skipped")
            return null
        }
        val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) resolved.network else null
        return Candidate(host, resolved.port, network, "mDNS ${resolved.serviceName}")
    }

    @Suppress("DEPRECATION")
    private fun resolve(
        info: NsdServiceInfo,
        timeoutMs: Long,
    ): NsdServiceInfo? {
        val done = CountDownLatch(1)
        var out: NsdServiceInfo? = null
        val ok =
            runCatching {
                nsd.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(
                            serviceInfo: NsdServiceInfo,
                            errorCode: Int,
                        ) {
                            log.w("wifi: couldn't resolve ${serviceInfo.serviceName} (error $errorCode)")
                            done.countDown()
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            out = serviceInfo
                            done.countDown()
                        }
                    },
                )
            }.isSuccess
        if (!ok) return null
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 3000L
    }
}
