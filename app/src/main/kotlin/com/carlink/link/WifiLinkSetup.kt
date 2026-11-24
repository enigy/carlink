package com.carlink.link

import com.carlink.ocbm.WifiLinkInfo
import com.carlink.ocbm.WifiLinkProvisioning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The Wi-Fi link's set-up (Settings › Adapter and about › Adapter connection): sends the phone hotspot's name, its WPA key
 * and this head unit's link key to the adapter (MGMT_WIFI_LINK), and removes them. It takes whatever session Carlink has
 * with the adapter ([Session]), however that session reached it: the USB bridge in M1, the Wi-Fi link itself to change
 * the hotspot, and later the adapter's set-up network (the plan's addendum 1). Nothing here knows USB.
 *
 * The password is hashed into the WPA key before it is sent ([WifiLinkProvisioning]) and kept nowhere; the key and the
 * hotspot's name are kept only once the adapter has taken them ([WifiLinkStore.setUp]).
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

        /** MGMT_WIFI_LINK with [body] (empty clears): the adapter's status, null with no answer. Blocking. */
        fun wifiLink(body: ByteArray): Int?
    }

    enum class Sent {
        /** The adapter took them; the session it came over stays. */
        SENT,

        /** The adapter took them over the Wi-Fi link, which now drops and comes back. */
        SENT_RECONNECTING,
        NO_SESSION,
        NOT_SUPPORTED,
        REFUSED,
        NO_ANSWER,
        BUSY,

        /** This head unit's KeyStore failed reading or keeping the link key: nothing changed here. */
        KEY_STORE,
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

    /** Send [ssid] and [password] (checked by the caller with [WifiLinkProvisioning.problem]). */
    suspend fun send(
        ssid: String,
        password: String,
    ): Sent {
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
            val status = withContext(Dispatchers.IO) { s.wifiLink(WifiLinkProvisioning.body(key, ssid, password)) }
            log("[WIFI] the adapter's answer to the hotspot and link key: ${status ?: "none"}${if (overWifi) " (over Wi-Fi)" else ""}")
            return when (status) {
                null -> Sent.NO_ANSWER
                0 -> keep(key, ssid, overWifi)
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

    /** Remove the link: on the adapter when a session is up (over either road), and here in any case. */
    suspend fun remove(): Removed {
        if (!sendingState.compareAndSet(false, true)) return Removed.ADAPTER_KEPT
        try {
            val s = session()?.takeIf { it.takesWifiLink }
            val status = s?.let { withContext(Dispatchers.IO) { it.wifiLink(ByteArray(0)) } }
            withContext(Dispatchers.IO) { store.forget() }
            log("[WIFI] the Wi-Fi link removed here; the adapter's answer: ${status ?: if (s == null) "no session" else "none"}")
            return when {
                s == null -> Removed.HERE_ONLY
                status == 0 -> Removed.REMOVED
                else -> Removed.ADAPTER_KEPT
            }
        } finally {
            sendingState.value = false
        }
    }
}
