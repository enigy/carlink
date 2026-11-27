package com.carlink.ocbm

import org.json.JSONObject

/**
 * MGMT_INFO's `"wifi_link"` object (the Wi-Fi link plan, contract item 5, and its ADDENDUM 24 item 9): the link's mode,
 * where it stands, and the channel (and, on the AP link, the band) of the adapter's own Wi-Fi. It holds no network name,
 * key or address. An adapter without the Wi-Fi link sends no such object.
 *
 * ```json
 * "wifi_link": {"mode": "ap", "state": "off" | "up" | "linked", "ap_band": "2.4" | "5", "ap_channel": 6}
 * "wifi_link": {"mode": "hotspot", "state": "off" | "joining" | "joined" | "linked", "channel": 149, "ap_channel": 36}
 * ```
 *
 * An adapter from before ADDENDUM 24 sends the second form without `mode` ([mode] null).
 */
data class WifiLinkInfo(
    val state: State,
    /** The hotspot's channel; 0 when not joined or not known (the hotspot link only). */
    val channel: Int,
    /** The adapter's own Wi-Fi channel; 0 when not known. */
    val apChannel: Int,
    /** The link's mode; null from an adapter that predates ADDENDUM 24, which knows only the hotspot link. */
    val mode: Mode? = null,
    /** The band of the adapter's own Wi-Fi on the AP link: `"2.4"` or `"5"`; null when not said, or not one of those. */
    val apBand: String? = null,
) {
    enum class Mode(
        val code: String,
    ) {
        /** The head unit joins the adapter's own Wi-Fi (ADDENDUM 24, the AP link). */
        AP("ap"),

        /** The adapter and the head unit join the phone's hotspot (the link before ADDENDUM 24). */
        HOTSPOT("hotspot"),

        /** No Wi-Fi link set up on the adapter. */
        OFF("off"),

        /** A mode this Carlink doesn't know (a newer adapter). */
        UNKNOWN(""),
    }

    enum class State(
        val code: String,
    ) {
        /** No Wi-Fi link on the adapter (on the AP link: its Wi-Fi isn't up). */
        OFF("off"),

        /** Set up, looking for the hotspot or joining it. */
        JOINING("joining"),

        /** On the hotspot, no host linked over it. */
        JOINED("joined"),

        /** The AP link: the adapter's own Wi-Fi is up and its listener with it, no host linked over it. */
        UP("up"),

        /** A host is linked over Wi-Fi. */
        LINKED("linked"),

        /**
         * This adapter's radio can't run a station beside its own access point (a driver with one wireless interface):
         * it can never join a hotspot. Final for the adapter's boot (the adapter side, 24 Nov night).
         */
        UNSUPPORTED("unsupported"),

        /** A state this Carlink doesn't know (a newer adapter). */
        UNKNOWN(""),
    }

    /**
     * Both channels known and different. The adapter has one radio for its own Wi-Fi and the hotspot; M1 doesn't move its
     * channel during a session, so the radio has to serve two channels. Never on the AP link: there is no hotspot.
     */
    val channelsDiffer: Boolean get() = mode != Mode.AP && channel > 0 && apChannel > 0 && channel != apChannel

    companion object {
        private val BANDS = setOf("2.4", "5")

        /** From an MGMT_INFO document; null when it has no `wifi_link` object. */
        fun from(info: JSONObject): WifiLinkInfo? {
            val o = info.optJSONObject("wifi_link") ?: return null
            val code = o.optString("state")
            val mode = if (o.has("mode")) o.optString("mode").let { m -> Mode.entries.firstOrNull { it.code.isNotEmpty() && it.code == m } ?: Mode.UNKNOWN } else null
            return WifiLinkInfo(
                state = State.entries.firstOrNull { it.code.isNotEmpty() && it.code == code } ?: State.UNKNOWN,
                channel = o.optInt("channel", 0).coerceAtLeast(0),
                apChannel = o.optInt("ap_channel", 0).coerceAtLeast(0),
                mode = mode,
                apBand = o.optString("ap_band").takeIf { it in BANDS },
            )
        }

        /** From MGMT_INFO's JSON text; null when it doesn't parse or has no `wifi_link` object. */
        fun parse(json: String): WifiLinkInfo? = runCatching { JSONObject(json) }.getOrNull()?.let(::from)
    }
}
