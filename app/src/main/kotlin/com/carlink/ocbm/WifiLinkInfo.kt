package com.carlink.ocbm

import org.json.JSONObject

/**
 * MGMT_INFO's `"wifi_link"` object (the Wi-Fi link plan, contract item 5): where the adapter's station link to the phone's
 * hotspot stands, the hotspot's channel and the channel of the adapter's own Wi-Fi (the iPhone's CarPlay link). It holds
 * no hotspot name, key or address. An adapter without the Wi-Fi link sends no such object.
 *
 * ```json
 * "wifi_link": {"state": "off" | "joining" | "joined" | "linked", "channel": 149, "ap_channel": 36}
 * ```
 */
data class WifiLinkInfo(
    val state: State,
    /** The hotspot's channel; 0 when not joined or not known. */
    val channel: Int,
    /** The adapter's own Wi-Fi channel; 0 when not known. */
    val apChannel: Int,
) {
    enum class State(
        val code: String,
    ) {
        /** No Wi-Fi link on the adapter: it joins no hotspot. */
        OFF("off"),

        /** Set up, looking for the hotspot or joining it. */
        JOINING("joining"),

        /** On the hotspot, no host linked over it. */
        JOINED("joined"),

        /** A host is linked over the hotspot. */
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
     * channel during a session, so the radio has to serve two channels.
     */
    val channelsDiffer: Boolean get() = channel > 0 && apChannel > 0 && channel != apChannel

    companion object {
        /** From an MGMT_INFO document; null when it has no `wifi_link` object. */
        fun from(info: JSONObject): WifiLinkInfo? {
            val o = info.optJSONObject("wifi_link") ?: return null
            val code = o.optString("state")
            return WifiLinkInfo(
                state = State.entries.firstOrNull { it.code.isNotEmpty() && it.code == code } ?: State.UNKNOWN,
                channel = o.optInt("channel", 0).coerceAtLeast(0),
                apChannel = o.optInt("ap_channel", 0).coerceAtLeast(0),
            )
        }

        /** From MGMT_INFO's JSON text; null when it doesn't parse or has no `wifi_link` object. */
        fun parse(json: String): WifiLinkInfo? = runCatching { JSONObject(json) }.getOrNull()?.let(::from)
    }
}
