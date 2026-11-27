package com.carlink.link

import com.carlink.ocbm.OcbwLink
import com.carlink.ocbm.RawBulkTransport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.Closeable

/**
 * Finds the adapter on Wi-Fi and links to it (OCBW handshake done), in this order:
 *
 * 1. a debug build's bench address ([Port.override]), and nothing else: the bench emulator can't see mDNS;
 * 2. none at all when the head unit is on no Wi-Fi network: there is nowhere to look (the start goes on at once);
 * 3. the addresses tried directly, again every [Timing.lastRetryMs]: on the adapter's own Wi-Fi (the AP link, the plan's
 *    ADDENDUM 24) the network's gateway, which is the adapter ([Port.gateways]); then the address the adapter answered on
 *    last ([Port.lastAddress]), when it is another;
 * 4. the box's mDNS advert (`_ocbm._tcp`) whose TXT `id` is this link key's ([OcbwLink.boxId]): any other adapter on the
 *    same network is passed over, and a box with another key never even matches.
 *
 * Until the deadline [find] is given. Android stays behind [Port] ([WifiBoxFinder]), so the order is unit-tested.
 */
class WifiBoxSearch(
    private val port: Port,
    private val timing: Timing = Timing(),
) {
    data class Timing(
        /** The TCP connect to a gateway or the last address, which on one Wi-Fi hop answers in milliseconds or not at all. */
        val lastConnectMs: Int = 2_000,
        /** The TCP connect to an address mDNS resolved. */
        val foundConnectMs: Int = 4_000,
        /** How often the gateways and the last address are tried again while the search lasts. */
        val lastRetryMs: Long = 3_000,
        /** Between tries of the bench address. */
        val overrideRetryMs: Long = 1_000,
        /** The longest one blocking wait for an advert, so a cancelled start stops within it. */
        val pollMs: Long = 500,
    )

    /**
     * One address the adapter may answer on. [network] is the Android network to bind to (null: the default). [remember]:
     * keep it as the last address once it links (not the bench's, which is only for this process).
     */
    data class Candidate(
        val host: String,
        val port: Int,
        val how: String,
        val network: android.net.Network? = null,
        val remember: Boolean = true,
    )

    /** How one link attempt ended. */
    sealed interface Attempt {
        data class Linked(
            val transport: RawBulkTransport,
        ) : Attempt

        data class Failed(
            val why: String,
            /** The box answered but proved another key, or refused ours. */
            val keyRefused: Boolean = false,
        ) : Attempt
    }

    /** How a search ended. */
    sealed interface Outcome {
        data class Found(
            val transport: RawBulkTransport,
            val at: Candidate,
        ) : Outcome

        data class NotFound(
            val why: Why,
        ) : Outcome
    }

    enum class Why {
        /** The head unit is on no Wi-Fi network. */
        NO_WIFI,

        /** Nothing answered with this link key. */
        NO_ANSWER,

        /** An adapter answered and refused this head unit's key, or proved another. */
        KEY_REFUSED,
    }

    interface Port {
        /** The bench address (debug builds only); null otherwise. */
        fun override(): Candidate?

        /** The head unit is on at least one Wi-Fi network. */
        fun onWifi(): Boolean

        /**
         * On the AP link: the gateway of each Wi-Fi network the head unit is on now, bound to that network; empty on the
         * hotspot link, or with no gateway known. Asked again at each round, so a network joined during a search counts.
         */
        fun gateways(): List<Candidate>

        /** Where the adapter answered last, bound to the Wi-Fi network that reaches it; null when none is known. */
        fun lastAddress(): Candidate?

        /** Start browsing for adverts carrying [boxId]; null when discovery can't start. */
        fun browse(boxId: String): Browse?

        /** TCP connect within [connectMs] and the OCBW handshake with [key]. Blocking. */
        fun link(
            candidate: Candidate,
            key: ByteArray,
            connectMs: Int,
        ): Attempt

        fun log(line: String)
    }

    /** A running mDNS browse; [next] gives each resolved advert carrying the id once. */
    interface Browse : Closeable {
        /** The next advert carrying the id within [waitMs], resolved; null when none came. Blocking. */
        fun next(waitMs: Long): Candidate?
    }

    /** Search until [deadlineMs] (on [now]'s clock). Cancellable between steps; each blocking step is bounded. */
    suspend fun find(
        key: ByteArray,
        deadlineMs: Long,
        now: () -> Long,
    ): Outcome {
        port.override()?.let { return untilDeadline(Look(key, now), it, deadlineMs) }
        if (!port.onWifi()) return Outcome.NotFound(Why.NO_WIFI)
        val look = Look(key, now)
        val browse = port.browse(OcbwLink.boxId(key))
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                look.direct()?.let { return it }
                val left = deadlineMs - now()
                if (left <= 0) break
                look.advert(browse, minOf(left, timing.pollMs))?.let { return it }
            }
        } finally {
            browse?.let { runCatching { it.close() } }
        }
        return look.notFound()
    }

    /** The bench address [at] alone, again every [Timing.overrideRetryMs] until the deadline. */
    private suspend fun untilDeadline(
        look: Look,
        at: Candidate,
        deadlineMs: Long,
    ): Outcome {
        port.log("[WIFI] the bench address ${at.host}:${at.port} stands in for mDNS")
        while (true) {
            currentCoroutineContext().ensureActive()
            look.attempt(at, timing.foundConnectMs)?.let { return it }
            val left = deadlineMs - look.now()
            if (left <= 0) return look.notFound()
            delay(minOf(left, timing.overrideRetryMs))
        }
    }

    /** One search: when the addresses tried directly last were, and whether a box refused the key. */
    private inner class Look(
        private val key: ByteArray,
        val now: () -> Long,
    ) {
        private var refused = false
        private var directAt: Long? = null

        /**
         * The gateways, then the last address when it is another, each once, when they are due again; found when one
         * linked. Both asked of [port] again each round: the head unit may join the adapter's Wi-Fi during the search.
         */
        fun direct(): Outcome.Found? {
            val before = directAt
            if (before != null && now() - before < timing.lastRetryMs) return null
            directAt = now()
            val gateways = port.gateways()
            val last = port.lastAddress()?.takeIf { l -> gateways.none { it.host == l.host && it.port == l.port } }
            for (at in gateways + listOfNotNull(last)) attempt(at, timing.lastConnectMs)?.let { return it }
            return null
        }

        /** The next advert carrying the id within [waitMs], tried; found when it linked. */
        suspend fun advert(
            browse: Browse?,
            waitMs: Long,
        ): Outcome.Found? {
            if (browse == null) {
                delay(waitMs)
                return null
            }
            return browse.next(waitMs)?.let { attempt(it, timing.foundConnectMs) }
        }

        fun attempt(
            at: Candidate,
            connectMs: Int,
        ): Outcome.Found? =
            when (val a = port.link(at, key, connectMs)) {
                is Attempt.Linked -> Outcome.Found(a.transport, at)
                is Attempt.Failed -> {
                    refused = refused || a.keyRefused
                    null
                }
            }

        fun notFound(): Outcome = Outcome.NotFound(if (refused) Why.KEY_REFUSED else Why.NO_ANSWER)
    }

    companion object {
        /** Whether an advert's TXT record names [boxId] (`id=`, 16 hex digits, either case). */
        fun isOurs(
            attributes: Map<String, ByteArray?>,
            boxId: String,
        ): Boolean {
            val id = attributes["id"] ?: return false
            return String(id, Charsets.US_ASCII).trim().equals(boxId, ignoreCase = true)
        }
    }
}
