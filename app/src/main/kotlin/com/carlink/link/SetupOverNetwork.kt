package com.carlink.link

import com.carlink.logging.ProbeLog
import com.carlink.ocbm.Ocbm
import com.carlink.ocbm.OcbmClient
import com.carlink.ocbm.RawBulkTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * M3 (approved 24 Nov): the first Wi-Fi set-up of an adapter Carlink has no other session with (no USB bridge, USB
 * (direct) refused). Such an adapter, with no link and no host, offers its own set-up network for 10 minutes after
 * power-up ([com.carlink.ocbm.OcbsSetup]). Carlink joins it ([Port.join]: Android's network request, one system dialog,
 * or a network the driver joined by hand in the car's Wi-Fi settings), runs the set-up exchange on a socket bound to it,
 * and sends the hotspot through the same [WifiLinkSetup] as any session. Then it lets the network go, so the head unit
 * goes back to the phone's hotspot, where the Wi-Fi search finds the adapter.
 */
class SetupOverNetwork(
    private val port: Port,
    private val store: WifiLinkStore,
    private val log: ProbeLog.Logger,
    private val helloMs: Long = HELLO_MS,
) {
    /** The set-up network, joined; [release] lets it go. [network] binds the socket (null: the default, in tests). */
    class Joined(
        val network: android.net.Network?,
        val byHand: Boolean,
        val release: () -> Unit,
    )

    interface Port {
        /** The set-up network: one already joined by hand, else asked of Android; null when none was joined. */
        suspend fun join(): Joined?

        /** The set-up port on the adapter, the exchange done: OCBW records over it. Blocking; throws IOException. */
        fun connect(joined: Joined): RawBulkTransport
    }

    enum class Phase { IDLE, JOINING, EXCHANGING, DONE }

    enum class Result {
        SENT,
        NOT_JOINED,

        /** Nothing answered on the set-up port, or the exchange or HELLO failed: no adapter offering it. */
        NO_ADAPTER,
        NOT_SUPPORTED,
        REFUSED,
        NO_ANSWER,
        BUSY,
    }

    private val phaseState = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = phaseState.asStateFlow()

    private val resultState = MutableStateFlow<Result?>(null)

    /** How the last set-up ended; null before one, or while one runs. */
    val result: StateFlow<Result?> = resultState.asStateFlow()

    /** Join, exchange, send [ssid] and [password] (checked by the caller), and let the network go. */
    suspend fun run(
        ssid: String,
        password: String,
    ): Result {
        if (phaseState.value == Phase.JOINING || phaseState.value == Phase.EXCHANGING) return Result.BUSY
        resultState.value = null
        phaseState.value = Phase.JOINING
        val r =
            try {
                val joined = port.join()
                if (joined == null) Result.NOT_JOINED else exchange(joined, ssid, password)
            } finally {
                phaseState.value = Phase.DONE
            }
        resultState.value = r
        log.i("set-up over the adapter's set-up network: $r")
        return r
    }

    private suspend fun exchange(
        joined: Joined,
        ssid: String,
        password: String,
    ): Result {
        phaseState.value = Phase.EXCHANGING
        try {
            val t =
                try {
                    withContext(Dispatchers.IO) { port.connect(joined) }
                } catch (e: IOException) {
                    log.w("set-up port: ${e.javaClass.simpleName}: ${e.message}")
                    return Result.NO_ADAPTER
                }
            val c = OcbmClient(t, log)
            c.start()
            try {
                if (!withContext(Dispatchers.IO) { c.hello(helloMs, untilLinkDead = true) }) return Result.NO_ADAPTER
                val setup = WifiLinkSetup(store, { sessionOf(c) }) { log.i(it) }
                return when (setup.send(ssid, password)) {
                    WifiLinkSetup.Sent.SENT, WifiLinkSetup.Sent.SENT_RECONNECTING -> Result.SENT
                    WifiLinkSetup.Sent.NOT_SUPPORTED -> Result.NOT_SUPPORTED
                    WifiLinkSetup.Sent.REFUSED -> Result.REFUSED
                    WifiLinkSetup.Sent.NO_ANSWER -> Result.NO_ANSWER
                    WifiLinkSetup.Sent.NO_SESSION -> Result.NO_ADAPTER
                    WifiLinkSetup.Sent.BUSY -> Result.BUSY
                }
            } finally {
                withContext(Dispatchers.IO) { c.stop(sendStop = false) }
            }
        } finally {
            runCatching { joined.release() }
        }
    }

    /** The set-up session as [WifiLinkSetup] sees any session. The box applies the link after its ACK and closes this one. */
    private fun sessionOf(c: OcbmClient): WifiLinkSetup.Session =
        object : WifiLinkSetup.Session {
            override val takesWifiLink: Boolean get() = c.supportsWifiLink
            override val overWifi: Boolean = true

            override fun wifiLink(body: ByteArray): Int? = c.mgmtAction(Ocbm.MGMT_WIFI_LINK, body, ANSWER_MS)
        }

    private companion object {
        const val HELLO_MS = 5_000L
        const val ANSWER_MS = 5_000L
    }
}
