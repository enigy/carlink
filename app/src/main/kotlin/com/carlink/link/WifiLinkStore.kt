package com.carlink.link

import android.content.Context
import android.content.SharedPreferences
import com.carlink.ocbm.OcbwLink
import com.carlink.ui.settings.Labelled
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How Carlink reaches the adapter (Settings › Adapter and about › Adapter connection). Persisted by [code], never ordinal.
 *
 * USB is today's way, through the sideloaded USB bridge, and the default. Wi-Fi is the link over the phone's hotspot
 * ([com.carlink.ocbm.WifiBulkTransport]). A later "USB (direct)" (notes/carlink.md) would be a third code, so "usb" keeps
 * meaning what it means today.
 */
enum class AdapterLink(
    val code: String,
    override val label: String,
) : Labelled {
    USB("usb", "USB"),
    WIFI("wifi", "Wi-Fi"),
    ;

    companion object {
        val DEFAULT = USB

        fun fromCode(code: String?): AdapterLink = entries.find { it.code == code } ?: DEFAULT
    }
}

/**
 * What Carlink knows about the adapter's Wi-Fi link: the connection the driver chose, the hotspot the adapter was told to
 * join (its name only: the password is hashed and sent, never kept), whether a link key is held, and the address the
 * adapter answered on last, which is tried first.
 */
data class WifiLinkSettings(
    val link: AdapterLink = AdapterLink.DEFAULT,
    /** The hotspot the adapter was told to join; empty until a send the adapter took. */
    val ssid: String = "",
    /** A link key is stored here, sealed by the Android Keystore. */
    val keyStored: Boolean = false,
    /** The stored key couldn't be opened on this head unit (its Keystore key is gone): set up again. */
    val keyUnreadable: Boolean = false,
    val lastHost: String? = null,
    val lastPort: Int = OcbwLink.PORT,
) {
    /** The adapter took a hotspot and a key this head unit can still read. */
    val setUp: Boolean get() = keyStored && !keyUnreadable && ssid.isNotEmpty()

    val wifiChosen: Boolean get() = link == AdapterLink.WIFI

    /** Connect over Wi-Fi: chosen and set up. */
    val wifiActive: Boolean get() = wifiChosen && setUp

    /**
     * The USB bridge is needed only for USB. Once the driver chose Wi-Fi, set up or not, no screen asks for it (the
     * owner, 23 Nov: some users cannot sideload it).
     */
    val bridgeNeeded: Boolean get() = !wifiChosen
}

/**
 * Seals the link key: [seal] gives text to keep in preferences, [open] gives the key back, or null when it can't (the
 * Keystore key is gone, or the text was made elsewhere). Real ones keep their key in the Android Keystore
 * ([KeystoreLinkKeySealer]); tests pass a fake.
 */
interface LinkKeySealer {
    fun seal(plain: ByteArray): String

    fun open(sealed: String): ByteArray?
}

/**
 * The persisted [WifiLinkSettings], in the style of [com.carlink.ui.settings.DriveSideStore], one per process. The link
 * key is kept only sealed ([LinkKeySealer]), never in plain preferences, and is opened only when Carlink connects or
 * sends it ([linkKey]).
 */
class WifiLinkStore internal constructor(
    context: Context,
    private val sealer: LinkKeySealer,
    prefsName: String = PREFS_NAME,
) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val flow: StateFlow<WifiLinkSettings> = state.asStateFlow()

    val current: WifiLinkSettings get() = state.value

    private fun load(): WifiLinkSettings =
        WifiLinkSettings(
            link = AdapterLink.fromCode(prefs.getString(KEY_LINK, null)),
            ssid = prefs.getString(KEY_SSID, null).orEmpty(),
            keyStored = prefs.contains(KEY_SEALED),
            keyUnreadable = prefs.getBoolean(KEY_UNREADABLE, false),
            lastHost = prefs.getString(KEY_LAST_HOST, null),
            lastPort = prefs.getInt(KEY_LAST_PORT, OcbwLink.PORT),
        )

    @Synchronized
    fun setLink(link: AdapterLink) {
        if (link == state.value.link) return
        prefs.edit().putString(KEY_LINK, link.code).apply()
        state.value = state.value.copy(link = link)
    }

    /**
     * The link key, opened; null when none is stored or it can't be opened (then [WifiLinkSettings.keyUnreadable], and
     * Settings asks for the hotspot again). Blocking: a Keystore call.
     */
    fun linkKey(): ByteArray? {
        val sealed = prefs.getString(KEY_SEALED, null) ?: return null
        val key = runCatching { sealer.open(sealed) }.getOrNull()?.takeIf { it.size == OcbwLink.KEY_LEN }
        if (key == null) markUnreadable()
        return key
    }

    /** The key to send: the one already held, so a link that changes hotspot keeps its key, else a new random one. */
    fun keyForSending(): ByteArray = linkKey() ?: OcbwLink.random32()

    /** The adapter took [key] and the hotspot [ssid]: keep both (the key sealed). The last address is forgotten. */
    @Synchronized
    fun setUp(
        key: ByteArray,
        ssid: String,
    ) {
        require(key.size == OcbwLink.KEY_LEN) { "a 32-byte link key" }
        prefs
            .edit()
            .putString(KEY_SEALED, sealer.seal(key))
            .putString(KEY_SSID, ssid)
            .remove(KEY_UNREADABLE)
            .remove(KEY_LAST_HOST)
            .remove(KEY_LAST_PORT)
            .apply()
        state.value = state.value.copy(ssid = ssid, keyStored = true, keyUnreadable = false, lastHost = null, lastPort = OcbwLink.PORT)
    }

    @Synchronized
    fun rememberAddress(
        host: String,
        port: Int,
    ) {
        if (host == state.value.lastHost && port == state.value.lastPort) return
        prefs
            .edit()
            .putString(KEY_LAST_HOST, host)
            .putInt(KEY_LAST_PORT, port)
            .apply()
        state.value = state.value.copy(lastHost = host, lastPort = port)
    }

    /** Remove: the key, the hotspot and the address go, and the connection is USB again. */
    @Synchronized
    fun forget() {
        prefs
            .edit()
            .remove(KEY_SEALED)
            .remove(KEY_SSID)
            .remove(KEY_UNREADABLE)
            .remove(KEY_LAST_HOST)
            .remove(KEY_LAST_PORT)
            .putString(KEY_LINK, AdapterLink.USB.code)
            .apply()
        state.value = WifiLinkSettings(link = AdapterLink.USB)
    }

    @Synchronized
    private fun markUnreadable() {
        if (state.value.keyUnreadable) return
        prefs.edit().putBoolean(KEY_UNREADABLE, true).apply()
        state.value = state.value.copy(keyUnreadable = true)
    }

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these loses the link or the choice.
        private const val PREFS_NAME = "carlink_wifi_link"
        private const val KEY_LINK = "link"
        private const val KEY_SSID = "ssid"
        private const val KEY_SEALED = "link_key_sealed_v1"
        private const val KEY_UNREADABLE = "link_key_unreadable"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_LAST_PORT = "last_port"

        @Volatile private var instance: WifiLinkStore? = null

        fun get(context: Context): WifiLinkStore =
            instance ?: synchronized(this) {
                instance ?: WifiLinkStore(context, KeystoreLinkKeySealer()).also { instance = it }
            }
    }
}
