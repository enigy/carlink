package com.carlink.link

import com.carlink.logging.ProbeLog
import com.carlink.ocbm.Ocbm
import com.carlink.ocbm.OcbmClient
import com.carlink.ocbm.OcbsSetup
import com.carlink.ocbm.RawBulkTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M3 (approved 24 Nov): the first Wi-Fi set-up of an adapter Carlink has no other session with (no USB bridge, USB
 * (direct) refused). Such an adapter, with no link and no host, offers its own set-up network for 10 minutes after
 * power-up ([com.carlink.ocbm.OcbsSetup]). Carlink joins it ([Port.join]: Android's network request, one system dialog,
 * or a network the driver joined by hand in the car's Wi-Fi settings), runs the set-up exchange on a socket bound to it,
 * asks the driver one question ([Confirm]: the set-up code, which the adapter now shows as its Bluetooth name), and only
 * when the driver says they match sends CT_HELLO and the hotspot, through the same [WifiLinkSetup] as any session. Then it
 * lets the network go, so the head unit goes back to the phone's hotspot, where the Wi-Fi search finds the adapter.
 */
class SetupOverNetwork(
    private val port: Port,
    private val store: WifiLinkStore,
    private val log: ProbeLog.Logger,
    private val helloMs: Long = HELLO_MS,
    private val confirmMs: Long = CONFIRM_MS,
) {
    /**
     * The set-up network, joined; [release] lets it go. [network] binds the socket (null: the default, in tests). [name]:
     * its SSID when Android lets Carlink read it (fine location granted, location on), else null.
     */
    class Joined(
        val network: android.net.Network?,
        val byHand: Boolean,
        val name: String? = null,
        val release: () -> Unit,
    )

    /** The set-up link: the transport, and the exchange's transcript (host_pub | box_pub), whose code the driver checks. */
    class Linked(
        val transport: RawBulkTransport,
        val transcript: ByteArray,
    )

    /**
     * The one question before anything is sent (the plan's ADDENDUM 5): the set-up [code], which the adapter now shows as its
     * Bluetooth name (`CarLink code NNNN`), so the driver sees that Carlink reached THEIR adapter. [networkName]: the set-up
     * network Carlink joined, null when Android won't name it; the question then also asks the driver to check the car's
     * Wi-Fi settings (the review's 24 Nov finding 6). A name that can be read has been checked already ([Port.join]): a
     * network joined by hand is taken only when its name is a set-up network's.
     */
    class Confirm(
        val code: String,
        val networkName: String?,
    )

    interface Port {
        /** The set-up network: one already joined by hand, else asked of Android; null when none was joined. */
        suspend fun join(): Joined?

        /** The set-up port on the adapter, the exchange done: OCBW records over it. Blocking; throws IOException. */
        fun connect(joined: Joined): Linked
    }

    enum class Phase { IDLE, JOINING, EXCHANGING, CONFIRMING, DONE }

    enum class Result {
        SENT,
        NOT_JOINED,

        /** Nothing answered on the set-up port, or the exchange or HELLO failed: no adapter offering it. */
        NO_ADAPTER,
        NOT_SUPPORTED,
        REFUSED,
        NO_ANSWER,
        BUSY,
        KEY_STORE,

        /** The driver didn't answer the code in time: nothing was sent. */
        NOT_CONFIRMED,

        /** The driver said the codes don't match: nothing was sent, the link closed. Another adapter or network answered. */
        NO_MATCH,

        /** The link ended while the driver was asked: nothing was sent. */
        LINK_LOST,
    }

    /** How the question ended; [result] is what the set-up then ends in, null to go on. */
    private enum class Answer(
        val result: Result?,
    ) {
        MATCH(null),
        NO_MATCH(Result.NO_MATCH),
        NONE(Result.NOT_CONFIRMED),
        LINK_LOST(Result.LINK_LOST),
    }

    private val phaseState = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = phaseState.asStateFlow()

    private val confirmState = MutableStateFlow<Confirm?>(null)

    /** The question the driver answers ([answer]) before anything is sent; null when none is asked. */
    val confirm: StateFlow<Confirm?> = confirmState.asStateFlow()

    @Volatile private var answerTo: CompletableDeferred<Answer>? = null

    /** The driver's answer to [confirm]: the code Carlink shows matches the adapter's Bluetooth name, or not. */
    fun answer(match: Boolean) {
        answerTo?.complete(if (match) Answer.MATCH else Answer.NO_MATCH)
    }

    private val resultState = MutableStateFlow<Result?>(null)

    /** How the last set-up ended; null before one, or while one runs. */
    val result: StateFlow<Result?> = resultState.asStateFlow()

    private val running = AtomicBoolean(false)

    /** Join, exchange, ask, send [ssid] and [password] (checked by the caller), and let the network go. */
    suspend fun run(
        ssid: String,
        password: String,
    ): Result {
        if (!running.compareAndSet(false, true)) return Result.BUSY
        resultState.value = null
        phaseState.value = Phase.JOINING
        val r =
            try {
                val joined = port.join()
                if (joined == null) Result.NOT_JOINED else exchange(joined, ssid, password)
            } finally {
                phaseState.value = Phase.DONE
                running.set(false)
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
            // Any failure of the platform's (a socket, the EC provider) ends in plain words, never in the Settings coroutine.
            @Suppress("TooGenericExceptionCaught")
            val t =
                try {
                    withContext(Dispatchers.IO) { port.connect(joined) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w("set-up port: ${e.javaClass.simpleName}: ${e.message}")
                    return Result.NO_ADAPTER
                }
            return session(t, joined.name, ssid, password)
        } finally {
            runCatching { joined.release() }
        }
    }

    /**
     * The set-up session: the question first, with nothing sent but the link's keepalives; then, on a match, CT_HELLO and
     * MGMT_WIFI_LINK, exactly the frames the adapter's set-up session takes. Every way out closes the link.
     */
    private suspend fun session(
        t: Linked,
        networkName: String?,
        ssid: String,
        password: String,
    ): Result {
        val c = OcbmClient(t.transport, log)
        val answer = CompletableDeferred<Answer>()
        // The link ending while the driver is asked (out of range, the adapter's power, its own limit) closes the question.
        c.onLinkDead = { answer.complete(Answer.LINK_LOST) }
        // From here each side sends an empty record after a second of nothing: the adapter shows its code at the first one.
        c.start()
        try {
            val code = OcbsSetup.code(t.transcript) ?: return Result.NO_ADAPTER
            ask(Confirm(code, networkName), answer).result?.let { return it }
            if (!withContext(Dispatchers.IO) { c.hello(helloMs, untilLinkDead = true) }) return Result.NO_ADAPTER
            return send(c, ssid, password)
        } finally {
            // Also when the set-up is cancelled while the driver is asked: the link must not outlive it.
            withContext(NonCancellable + Dispatchers.IO) { c.stop(sendStop = false) }
        }
    }

    /** Ask the driver; [Answer.NONE] when they don't answer within [confirmMs]. [answer] may already hold the link's end. */
    private suspend fun ask(
        question: Confirm,
        answer: CompletableDeferred<Answer>,
    ): Answer {
        answerTo = answer
        phaseState.value = Phase.CONFIRMING
        if (!answer.isCompleted) {
            confirmState.value = question
            log.i("set-up: the code is shown; waiting for the driver (${confirmMs / MS_PER_S} s)")
        }
        return try {
            (withTimeoutOrNull(confirmMs) { answer.await() } ?: Answer.NONE).also { log.i("set-up: the question ended: $it") }
        } finally {
            confirmState.value = null
            answerTo = null
            phaseState.value = Phase.EXCHANGING
        }
    }

    private suspend fun send(
        c: OcbmClient,
        ssid: String,
        password: String,
    ): Result {
        val setup = WifiLinkSetup(store, { sessionOf(c) }) { log.i(it) }
        return when (setup.send(ssid, password)) {
            WifiLinkSetup.Sent.SENT, WifiLinkSetup.Sent.SENT_RECONNECTING -> Result.SENT
            WifiLinkSetup.Sent.NOT_SUPPORTED, WifiLinkSetup.Sent.NO_STATION -> Result.NOT_SUPPORTED
            WifiLinkSetup.Sent.REFUSED -> Result.REFUSED
            WifiLinkSetup.Sent.NO_ANSWER -> Result.NO_ANSWER
            WifiLinkSetup.Sent.NO_SESSION -> Result.NO_ADAPTER
            WifiLinkSetup.Sent.BUSY -> Result.BUSY
            WifiLinkSetup.Sent.KEY_STORE -> Result.KEY_STORE
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

        /**
         * No answer in 150 s sends nothing (ADDENDUM 5: the driver opens a Bluetooth list). Inside the adapter's 180 s from
         * the exchange's end to MGMT_WIFI_LINK, with HELLO's 5 s and the ACK's 5 s after it.
         */
        const val CONFIRM_MS = 150_000L
        const val ANSWER_MS = 5_000L
        const val MS_PER_S = 1_000L
    }
}
