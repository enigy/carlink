package com.carlink.link

import com.carlink.ocbm.ApNetwork
import com.carlink.ocbm.WifiLinkInfo
import com.carlink.ocbm.WifiLinkProvisioning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The Wi-Fi link's set-up (Settings › Adapter Config › Adapter connection): sends this head unit's link key to the
 * adapter (MGMT_WIFI_LINK), and removes it. Since the plan's ADDENDUM 24 the set-up is the AP link's ([sendAp]): the
 * adapter keeps the key, runs its own Wi-Fi on the band the driver picked, and answers with that network's name and
 * password, which Settings shows so the driver can join it in the car's Wi-Fi settings. The hotspot link's set-up
 * ([send]: the phone hotspot's name and WPA key) is kept working, and no longer offered.
 *
 * It takes whatever session Carlink has with the adapter ([Session]), however that session reached it: USB (the bridge or
 * direct), the Wi-Fi link itself, or the adapter's set-up network ([SetupOverNetwork]). Nothing here knows USB. The key
 * and the network's password are kept only sealed, and only once the adapter has taken the key ([WifiLinkStore]).
 */
class WifiLinkSetup(
    private val store: WifiLinkStore,
    /** The live session with the adapter, or null with none. */
    private val session: () -> Session?,
    private val log: (String) -> Unit,
) {
    /** One live session with the adapter, as the set-up needs it. */
    interface Session {
        /** The adapter takes MGMT_WIFI_LINK (CAP_WIFI_LINK in its HELLO_ACK). */
        val takesWifiLink: Boolean

        /** This session rides the Wi-Fi link: the adapter drops it to apply a change, and Carlink connects again. */
        val overWifi: Boolean

        /**
         * MGMT_WIFI_LINK with [body] (empty clears): the adapter's whole MGMT_ACK payload (`[MGMT_ACK][verb][status]` and
         * what follows), null with no answer. Blocking.
         */
        fun wifiLink(body: ByteArray): ByteArray?
    }

    enum class Sent {
        /** The adapter took them; the session it came over stays. */
        SENT,

        /** The adapter took them over the Wi-Fi link, which drops and comes back if the adapter had to change anything. */
        SENT_RECONNECTING,
        NO_SESSION,
        NOT_SUPPORTED,
        REFUSED,
        NO_ANSWER,
        BUSY,

        /** This head unit's KeyStore failed reading or keeping the link key: nothing changed here. */
        KEY_STORE,

        /** The adapter says its radio can't join a hotspot (MGMT_INFO "unsupported"): nothing is sent (the hotspot link). */
        NO_STATION,
    }

    enum class Removed {
        /** Removed on the adapter and here. */
        REMOVED,

        /** Forgotten here only: no session with the adapter. */
        HERE_ONLY,

        /** Forgotten here, but the adapter refused, or didn't answer, to remove its own. */
        ADAPTER_KEPT,
    }

    private val sendingState = MutableStateFlow(false)

    /** A send or remove is in flight. */
    val busy: StateFlow<Boolean> = sendingState.asStateFlow()

    private val infoState = MutableStateFlow<WifiLinkInfo?>(null)

    /** The adapter's last `wifi_link` from MGMT_INFO, for this session; null when none is known. */
    val info: StateFlow<WifiLinkInfo?> = infoState.asStateFlow()

    /** Every MGMT_INFO the session hears ([com.carlink.CarlinkManager]): its `wifi_link`, or null without one. */
    fun onAdapterInfo(info: WifiLinkInfo?) {
        infoState.value = info
    }

    /** The session ended: what the adapter said is no longer known. */
    fun sessionEnded() {
        infoState.value = null
    }

    /**
     * The AP link (ADDENDUM 24): the link key and [band] (MGMT_WIFI_LINK v2). The adapter's answer names its own Wi-Fi,
     * kept with the key ([WifiLinkStore.setUpAp]). The same key again with the same band changes nothing on the adapter:
     * it is how Settings asks for the network's name and password again.
     */
    suspend fun sendAp(band: ApBand): Sent {
        val s = session() ?: return Sent.NO_SESSION
        if (!s.takesWifiLink) return Sent.NOT_SUPPORTED
        if (!sendingState.compareAndSet(false, true)) return Sent.BUSY
        try {
            val overWifi = s.overWifi
            val key = withContext(Dispatchers.IO) { store.keyForSending() }
            if (key == null) {
                log("[WIFI] the link key can't be read now (the KeyStore failed): nothing sent")
                return Sent.KEY_STORE
            }
            val reply = withContext(Dispatchers.IO) { s.wifiLink(WifiLinkProvisioning.apBody(key, band.wire)) }
            val answer = reply?.let(WifiLinkProvisioning::answer)
            val named = if (answer?.status == WifiLinkProvisioning.STATUS_OK && answer.network == null) " (its network not named)" else ""
            log("[WIFI] the adapter's answer to its own Wi-Fi and the link key: ${answer?.status ?: "none"}$named${if (overWifi) " (over Wi-Fi)" else ""}")
            return when (answer?.status) {
                null -> Sent.NO_ANSWER
                WifiLinkProvisioning.STATUS_OK -> keepAp(key, band, answer.network, overWifi)
                else -> Sent.REFUSED
            }
        } finally {
            sendingState.value = false
        }
    }

    /** The adapter took the AP link: keep the key and its network (sealed), or say the KeyStore couldn't. */
    private suspend fun keepAp(
        key: ByteArray,
        band: ApBand,
        network: ApNetwork?,
        overWifi: Boolean,
    ): Sent {
        if (withContext(Dispatchers.IO) { store.setUpAp(key, band, network) }) return if (overWifi) Sent.SENT_RECONNECTING else Sent.SENT
        log("[WIFI] the adapter took the link but its key couldn't be kept here: ${store.lastSealFailure}")
        return Sent.KEY_STORE
    }

    /**
     * The hotspot link, no longer offered: send [ssid] and [password] (checked by the caller with
     * [WifiLinkProvisioning.problem]).
     */
    suspend fun send(
        ssid: String,
        password: String,
    ): Sent {
        val s = session() ?: return Sent.NO_SESSION
        refusal(s)?.let { return it }
        if (!sendingState.compareAndSet(false, true)) return Sent.BUSY
        try {
            val overWifi = s.overWifi
            val key = withContext(Dispatchers.IO) { store.keyForSending() }
            if (key == null) {
                log("[WIFI] the link key can't be read now (the KeyStore failed): nothing sent")
                return Sent.KEY_STORE
            }
            val status = withContext(Dispatchers.IO) { s.wifiLink(WifiLinkProvisioning.body(key, ssid, password)) }?.let(::statusOf)
            log("[WIFI] the adapter's answer to the hotspot and link key: ${status ?: "none"}${if (overWifi) " (over Wi-Fi)" else ""}")
            return when (status) {
                null -> Sent.NO_ANSWER
                WifiLinkProvisioning.STATUS_OK -> keep(key, ssid, overWifi)
                else -> Sent.REFUSED
            }
        } finally {
            sendingState.value = false
        }
    }

    /** The adapter took them: keep the key (sealed) and the hotspot's name, or say the KeyStore couldn't. */
    private suspend fun keep(
        key: ByteArray,
        ssid: String,
        overWifi: Boolean,
    ): Sent {
        if (withContext(Dispatchers.IO) { store.setUp(key, ssid) }) return if (overWifi) Sent.SENT_RECONNECTING else Sent.SENT
        log("[WIFI] the adapter took the link but the key couldn't be kept here: ${store.lastSealFailure}")
        return Sent.KEY_STORE
    }

    /** What this adapter can't take: the verb, or (MGMT_INFO "unsupported") a hotspot at all; null when it can. */
    private fun refusal(s: Session): Sent? =
        when {
            !s.takesWifiLink -> Sent.NOT_SUPPORTED
            infoState.value?.state == WifiLinkInfo.State.UNSUPPORTED -> Sent.NO_STATION
            else -> null
        }

    /** Remove the link: on the adapter when a session is up (over either road), and here in any case. */
    suspend fun remove(): Removed {
        if (!sendingState.compareAndSet(false, true)) return Removed.ADAPTER_KEPT
        try {
            val s = session()?.takeIf { it.takesWifiLink }
            val status = s?.let { withContext(Dispatchers.IO) { it.wifiLink(ByteArray(0)) } }?.let(::statusOf)
            withContext(Dispatchers.IO) { store.forget() }
            log("[WIFI] the Wi-Fi link removed here; the adapter's answer: ${status ?: if (s == null) "no session" else "none"}")
            return when {
                s == null -> Removed.HERE_ONLY
                status == WifiLinkProvisioning.STATUS_OK -> Removed.REMOVED
                else -> Removed.ADAPTER_KEPT
            }
        } finally {
            sendingState.value = false
        }
    }

    /** An MGMT_ACK's status byte; null for a reply that isn't MGMT_WIFI_LINK's answer. */
    private fun statusOf(reply: ByteArray): Int? = WifiLinkProvisioning.answer(reply)?.status
}
