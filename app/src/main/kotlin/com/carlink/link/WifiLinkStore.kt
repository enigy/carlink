package com.carlink.link

import android.content.Context
import android.content.SharedPreferences
import com.carlink.ocbm.ApNetwork
import com.carlink.ocbm.OcbwLink
import com.carlink.ocbm.WifiLinkProvisioning
import com.carlink.ui.settings.Labelled
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How Carlink reaches the adapter (Settings › Adapter Config › Adapter connection), in the order Settings lists them.
 * Persisted by [code], never ordinal.
 *
 * - Wi-Fi: the link over Wi-Fi ([com.carlink.ocbm.WifiBulkTransport]); since the plan's ADDENDUM 24 the head unit joins
 *   the adapter's own Wi-Fi ([LinkMode.AP]).
 * - USB direct: Carlink opens the adapter itself, where Android lets it ([com.carlink.ocbm.UsbDirectTransport]).
 * - USB bridge: through the sideloaded USB bridge, the way Carlink has always reached it, and the default; code "usb",
 *   as before. With no bridge installed it asks Android for the adapter itself too (the owner, 24 Nov).
 */
enum class AdapterLink(
    val code: String,
    override val label: String,
) : Labelled {
    WIFI("wifi", "Wi-Fi"),
    USB_DIRECT("usb_direct", "USB direct"),
    USB_BRIDGE("usb", "USB bridge"),
    ;

    companion object {
        val DEFAULT = USB_BRIDGE

        fun fromCode(code: String?): AdapterLink = entries.find { it.code == code } ?: DEFAULT
    }
}

/** How the head unit and the adapter meet on Wi-Fi. Persisted by [code]. */
enum class LinkMode(
    val code: String,
) {
    /**
     * The plan's ADDENDUM 24 (the owner, 27 Nov): the head unit joins the adapter's own Wi-Fi network, the one the iPhone's
     * CarPlay uses, and finds the adapter at its gateway. The only mode Carlink's Settings set up.
     */
    AP("ap"),

    /** Before ADDENDUM 24: the adapter and the head unit both join the phone's hotspot. Kept working, no longer offered. */
    HOTSPOT("hotspot"),
    ;

    companion object {
        fun fromCode(code: String?): LinkMode? = entries.find { it.code == code }
    }
}

/**
 * The band of the adapter's own Wi-Fi on the AP link (ADDENDUM 24 item 4), as the driver picks it in Settings ›
 * Adapter Config; it goes to the adapter with each set-up ([WifiLinkProvisioning.apBody]). Persisted by [code], which is
 * also MGMT_INFO's `ap_band`.
 */
enum class ApBand(
    val code: String,
    override val label: String,
    val wire: Byte,
) : Labelled {
    GHZ_2_4("2.4", "2.4 GHz", WifiLinkProvisioning.BAND_2_4),
    GHZ_5("5", "5 GHz", WifiLinkProvisioning.BAND_5),
    ;

    companion object {
        /** Many head units, the owner's included, see only 2.4 GHz (ADDENDUM 24 item 4). */
        val DEFAULT = GHZ_2_4

        fun fromCode(code: String?): ApBand = ofCode(code) ?: DEFAULT

        /** The band [code] names (MGMT_INFO's `ap_band`), or null for none this Carlink knows. */
        fun ofCode(code: String?): ApBand? = entries.find { it.code == code }
    }
}

/**
 * What Carlink knows about the adapter's Wi-Fi link: the connection the driver chose, the link's mode, whether a link key
 * is held, and the address the adapter answered on last, which is tried first. On the AP link: the band the driver picked
 * and the adapter's network name (its password is kept only sealed, [WifiLinkStore.apPassword]). On the hotspot link: the
 * hotspot the adapter was told to join (its name only: the password is hashed and sent, never kept).
 */
data class WifiLinkSettings(
    val link: AdapterLink = AdapterLink.DEFAULT,
    /** The hotspot the adapter was told to join (the hotspot link); empty until a send the adapter took, and on the AP link. */
    val ssid: String = "",
    /** A link key is stored here, sealed by the Android Keystore. */
    val keyStored: Boolean = false,
    /** The stored key couldn't be opened on this head unit (its Keystore key is gone): set up again. */
    val keyUnreadable: Boolean = false,
    val lastHost: String? = null,
    val lastPort: Int = OcbwLink.PORT,
    /** The mode the adapter was set up in; null before any set-up. A hotspot set-up from before ADDENDUM 24 reads [LinkMode.HOTSPOT]. */
    val mode: LinkMode? = null,
    /** The band the driver picked for the adapter's own Wi-Fi: the one the next set-up sends. */
    val band: ApBand = ApBand.DEFAULT,
    /** The adapter's own Wi-Fi network's name, as its answer to the set-up gave it; empty when not known. */
    val apName: String = "",
    /** That network's password is kept here, sealed by the Android Keystore. */
    val apPasswordKept: Boolean = false,
) {
    /** The adapter took a key this head unit can still read, for its own Wi-Fi or a hotspot. */
    val setUp: Boolean get() = keyStored && !keyUnreadable && (mode == LinkMode.AP || ssid.isNotEmpty())

    /** Set up on the AP link: the head unit joins the adapter's own Wi-Fi. */
    val apLink: Boolean get() = setUp && mode == LinkMode.AP

    val wifiChosen: Boolean get() = link == AdapterLink.WIFI

    /** Connect over Wi-Fi: chosen and set up. */
    val wifiActive: Boolean get() = wifiChosen && setUp

    /**
     * The USB bridge is asked for only for USB bridge. Once the driver chose Wi-Fi, set up or not, or USB direct,
     * no screen asks for it (the owner, 23 Nov: some users cannot sideload it).
     */
    val bridgeNeeded: Boolean get() = link == AdapterLink.USB_BRIDGE
}

/**
 * Seals the link key: [seal] gives text to keep in preferences, [open] gives the key back, or null when it can't (the
 * Keystore key is gone, or the text was made elsewhere). Real ones keep their key in the Android Keystore
 * ([KeystoreLinkKeySealer]); tests pass a fake.
 */
interface LinkKeySealer {
    /** The sealed text for [plain]. May throw (the Keystore unavailable); the caller says so. */
    fun seal(plain: ByteArray): String

    fun open(sealed: String): Opened

    /** How an open ended: the key; [Gone] for good (no Keystore key, a bad tag, text not ours); [Unavailable] for now. */
    sealed interface Opened {
        class Key(
            val bytes: ByteArray,
        ) : Opened

        data object Gone : Opened

        /** A KeyStore or provider failure that may pass: try again later, the key is not lost. */
        data object Unavailable : Opened
    }
}

/**
 * The persisted [WifiLinkSettings], in the style of [com.carlink.ui.settings.DriveSideStore], one per process. The link
 * key and the adapter's Wi-Fi password are kept only sealed ([LinkKeySealer]), never in plain preferences, and are opened
 * only when Carlink connects or sends the key ([linkKey]) and when Settings shows the password ([apPassword]).
 */
class WifiLinkStore internal constructor(
    context: Context,
    private val sealer: LinkKeySealer,
    prefsName: String = PREFS_NAME,
) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** What the preferences hold, read once. */
    private val state =
        MutableStateFlow(
            prefs.getString(KEY_SSID, null).orEmpty().let { ssid ->
                WifiLinkSettings(
                    link = AdapterLink.fromCode(prefs.getString(KEY_LINK, null)),
                    ssid = ssid,
                    keyStored = prefs.contains(KEY_SEALED),
                    keyUnreadable = prefs.getBoolean(KEY_UNREADABLE, false),
                    lastHost = prefs.getString(KEY_LAST_HOST, null),
                    lastPort = prefs.getInt(KEY_LAST_PORT, OcbwLink.PORT),
                    // A set-up from before ADDENDUM 24 kept no mode: it was the hotspot link.
                    mode = LinkMode.fromCode(prefs.getString(KEY_MODE, null)) ?: LinkMode.HOTSPOT.takeIf { ssid.isNotEmpty() },
                    band = ApBand.fromCode(prefs.getString(KEY_BAND, null)),
                    apName = prefs.getString(KEY_AP_NAME, null).orEmpty(),
                    apPasswordKept = prefs.contains(KEY_AP_PASSWORD_SEALED),
                )
            },
        )

    val flow: StateFlow<WifiLinkSettings> = state.asStateFlow()

    val current: WifiLinkSettings get() = state.value

    @Synchronized
    fun setLink(link: AdapterLink) {
        if (link == state.value.link) return
        prefs.edit().putString(KEY_LINK, link.code).apply()
        state.value = state.value.copy(link = link)
    }

    /** The band the next set-up sends (Settings › Adapter Config); the adapter's own changes only with a set-up. */
    @Synchronized
    fun setBand(band: ApBand) {
        if (band == state.value.band) return
        prefs.edit().putString(KEY_BAND, band.code).apply()
        state.value = state.value.copy(band = band)
    }

    /**
     * The link key, opened; null when none is stored or it can't be opened (then [WifiLinkSettings.keyUnreadable], and
     * Settings asks for the hotspot again). Blocking: a Keystore call.
     */
    fun linkKey(): ByteArray? = (openKey() as? LinkKeySealer.Opened.Key)?.bytes

    /**
     * The link key, or why not. Only a definite failure ([LinkKeySealer.Opened.Gone]) marks it unreadable for good; a
     * KeyStore that fails for now leaves it as it is for the next try (the reviewer's 24 Nov finding 3). Blocking.
     */
    fun openKey(): LinkKeySealer.Opened {
        val sealed = prefs.getString(KEY_SEALED, null) ?: return LinkKeySealer.Opened.Gone
        val opened = runCatching { sealer.open(sealed) }.getOrElse { LinkKeySealer.Opened.Unavailable }
        if (opened is LinkKeySealer.Opened.Key && opened.bytes.size == OcbwLink.KEY_LEN) return opened
        if (opened is LinkKeySealer.Opened.Unavailable) return opened
        // Gone for good: say so once, and Settings asks for a set-up again.
        synchronized(this) {
            if (!state.value.keyUnreadable) {
                prefs.edit().putBoolean(KEY_UNREADABLE, true).apply()
                state.value = state.value.copy(keyUnreadable = true)
            }
        }
        return LinkKeySealer.Opened.Gone
    }

    /**
     * The key to send: the one already held, so a link that changes hotspot keeps its key, else (none, or lost for good)
     * a new random one; null while the KeyStore can't be read, so a held key isn't replaced by a passing failure.
     */
    fun keyForSending(): ByteArray? =
        when (val o = openKey()) {
            is LinkKeySealer.Opened.Key -> o.bytes
            LinkKeySealer.Opened.Gone -> OcbwLink.random32()
            LinkKeySealer.Opened.Unavailable -> null
        }

    /**
     * The adapter took [key] and the hotspot [ssid] (the hotspot link): keep both (the key sealed). The last address and
     * anything of the AP link are forgotten. False, with nothing kept, when the Keystore couldn't seal it.
     */
    @Synchronized
    fun setUp(
        key: ByteArray,
        ssid: String,
    ): Boolean {
        require(key.size == OcbwLink.KEY_LEN) { "a 32-byte link key" }
        val sealed = sealer.sealOrNull(key) { lastSealFailure = it } ?: return false
        prefs
            .edit()
            .putString(KEY_SEALED, sealed)
            .putString(KEY_SSID, ssid)
            .putString(KEY_MODE, LinkMode.HOTSPOT.code)
            .remove(KEY_AP_NAME)
            .remove(KEY_AP_PASSWORD_SEALED)
            .remove(KEY_UNREADABLE)
            .remove(KEY_LAST_HOST)
            .remove(KEY_LAST_PORT)
            .apply()
        state.value =
            state.value.copy(
                ssid = ssid,
                keyStored = true,
                keyUnreadable = false,
                lastHost = null,
                lastPort = OcbwLink.PORT,
                mode = LinkMode.HOTSPOT,
                apName = "",
                apPasswordKept = false,
            )
        return true
    }

    /**
     * The adapter took [key] for the AP link on [band], and named its own Wi-Fi [network] (null when its answer didn't):
     * keep the key and the network's password sealed, its name and the band plain. The last address and any hotspot are
     * forgotten. False, with nothing kept, when the Keystore couldn't seal either.
     */
    @Synchronized
    fun setUpAp(
        key: ByteArray,
        band: ApBand,
        network: ApNetwork?,
    ): Boolean {
        require(key.size == OcbwLink.KEY_LEN) { "a 32-byte link key" }
        val sealedKey = sealer.sealOrNull(key) { lastSealFailure = it } ?: return false
        val sealedPassword = network?.let { sealer.sealOrNull(it.password.toByteArray(Charsets.US_ASCII)) { why -> lastSealFailure = why } ?: return false }
        val edit =
            prefs
                .edit()
                .putString(KEY_SEALED, sealedKey)
                .putString(KEY_MODE, LinkMode.AP.code)
                .putString(KEY_BAND, band.code)
                .remove(KEY_SSID)
                .remove(KEY_UNREADABLE)
                .remove(KEY_LAST_HOST)
                .remove(KEY_LAST_PORT)
        if (network != null && sealedPassword != null) {
            edit.putString(KEY_AP_NAME, network.ssid).putString(KEY_AP_PASSWORD_SEALED, sealedPassword)
        } else {
            edit.remove(KEY_AP_NAME).remove(KEY_AP_PASSWORD_SEALED)
        }
        edit.apply()
        state.value =
            state.value.copy(
                ssid = "",
                keyStored = true,
                keyUnreadable = false,
                lastHost = null,
                lastPort = OcbwLink.PORT,
                mode = LinkMode.AP,
                band = band,
                apName = network?.ssid.orEmpty(),
                apPasswordKept = sealedPassword != null,
            )
        return true
    }

    /**
     * The adapter's own Wi-Fi password, opened, for Settings to show; null when none is kept or it can't be opened (then
     * a set-up again gets it). Empty for an open network. Blocking: a Keystore call. Never logged.
     */
    fun apPassword(): String? {
        val sealed = prefs.getString(KEY_AP_PASSWORD_SEALED, null) ?: return null
        val opened = runCatching { sealer.open(sealed) }.getOrNull()
        return (opened as? LinkKeySealer.Opened.Key)?.bytes?.toString(Charsets.US_ASCII)
    }

    /** Why the last seal failed, for the log; null before any. */
    @Volatile var lastSealFailure: String? = null
        private set

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

    /**
     * Remove: the key, the mode, the adapter's network and password (or the hotspot), the band and the address go, and the
     * connection is USB bridge again, the default.
     */
    @Synchronized
    fun forget() {
        prefs
            .edit()
            .remove(KEY_SEALED)
            .remove(KEY_SSID)
            .remove(KEY_UNREADABLE)
            .remove(KEY_LAST_HOST)
            .remove(KEY_LAST_PORT)
            .remove(KEY_MODE)
            .remove(KEY_BAND)
            .remove(KEY_AP_NAME)
            .remove(KEY_AP_PASSWORD_SEALED)
            .putString(KEY_LINK, AdapterLink.DEFAULT.code)
            .apply()
        state.value = WifiLinkSettings(link = AdapterLink.DEFAULT)
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
        private const val KEY_MODE = "mode"
        private const val KEY_BAND = "ap_band"
        private const val KEY_AP_NAME = "ap_name"
        private const val KEY_AP_PASSWORD_SEALED = "ap_password_sealed_v1"

        @Volatile private var instance: WifiLinkStore? = null

        fun get(context: Context): WifiLinkStore =
            instance ?: synchronized(this) {
                instance ?: WifiLinkStore(context, KeystoreLinkKeySealer()).also { instance = it }
            }
    }
}

/**
 * [plain] sealed, or null when the Keystore couldn't, the reason handed to [failed] for the log. Its own failures are
 * RuntimeExceptions (ProviderException) as well as checked ones.
 */
@Suppress("TooGenericExceptionCaught")
private fun LinkKeySealer.sealOrNull(
    plain: ByteArray,
    failed: (String) -> Unit,
): String? =
    try {
        seal(plain)
    } catch (e: Exception) {
        failed("${e.javaClass.simpleName}: ${e.message}")
        null
    }
