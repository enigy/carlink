package com.carlink.ocbm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Carlink knows about the adapter's Wi-Fi link: the 32-byte link key it set on the box over USB, the
 * hotspot it told the box to join (its name only: the password is hashed and sent, never stored), the
 * driver's toggle, and the address the box answered on last (tried first, before discovery).
 */
data class WifiLinkSettings(
    /** Settings › Adapter › "Connect over Wi-Fi". Only meaningful with a [keyHex]. */
    val enabled: Boolean = false,
    /** The link key as 64 hex digits; null until a provisioning the box acknowledged. */
    val keyHex: String? = null,
    /** The hotspot the box was told to join; empty = transport on, no hotspot. */
    val ssid: String = "",
    val lastHost: String? = null,
    val lastPort: Int = OcbwLink.PORT,
) {
    val provisioned: Boolean get() = keyHex != null

    /** Connect over Wi-Fi at the next start: on, and a key to prove. */
    val active: Boolean get() = enabled && provisioned

    val key: ByteArray? get() = keyHex?.let { hexBytes(it) }?.takeIf { it.size == OcbwLink.KEY_LEN }

    /** The id the box's mDNS advert carries (TXT `id=`), so Carlink picks its own box among several. */
    val boxId: String? get() = key?.let { OcbwLink.boxId(it) }
}

/**
 * The persisted [WifiLinkSettings], in the style of [com.carlink.ui.settings.DriveSideStore]. One instance
 * per process. The key sits in app-private storage, like the bridge's pins: anyone who can read Carlink's
 * data already holds the head unit.
 */
class WifiLinkStore private constructor(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val flow: StateFlow<WifiLinkSettings> = state.asStateFlow()

    val current: WifiLinkSettings get() = state.value

    private fun load() =
        WifiLinkSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            keyHex = prefs.getString(KEY_LINK_KEY, null),
            ssid = prefs.getString(KEY_SSID, "") ?: "",
            lastHost = prefs.getString(KEY_LAST_HOST, null),
            lastPort = prefs.getInt(KEY_LAST_PORT, OcbwLink.PORT),
        )

    /** The key to provision with: the one the box already holds if there is one, else a new random one. */
    fun keyForProvisioning(): ByteArray = current.key ?: OcbwLink.random32()

    /** The box acknowledged [key] and [ssid]: remember them and turn the toggle on. */
    @Synchronized
    fun provisioned(
        key: ByteArray,
        ssid: String,
    ) {
        prefs
            .edit()
            .putString(KEY_LINK_KEY, key.toHex())
            .putString(KEY_SSID, ssid)
            .putBoolean(KEY_ENABLED, true)
            .remove(KEY_LAST_HOST)
            .apply()
        state.value = load()
    }

    @Synchronized
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        state.value = state.value.copy(enabled = on)
    }

    @Synchronized
    fun rememberAddress(
        host: String,
        port: Int,
    ) {
        if (host == state.value.lastHost && port == state.value.lastPort) return
        prefs.edit().putString(KEY_LAST_HOST, host).putInt(KEY_LAST_PORT, port).apply()
        state.value = state.value.copy(lastHost = host, lastPort = port)
    }

    /** Forget the link (after the box cleared it, or when the driver removes it): USB only. */
    @Synchronized
    fun forget() {
        prefs.edit().clear().apply()
        state.value = WifiLinkSettings()
    }

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these orphans the stored link.
        private const val PREFS_NAME = "carlink_wifi_link"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LINK_KEY = "link_key"
        private const val KEY_SSID = "ssid"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_LAST_PORT = "last_port"

        @Volatile private var instance: WifiLinkStore? = null

        fun get(context: Context): WifiLinkStore =
            instance ?: synchronized(this) {
                instance ?: WifiLinkStore(context).also { instance = it }
            }
    }
}
