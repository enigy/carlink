package com.carlink.link

import com.carlink.ocbm.OcbsSetup

/** The set-up network's names as Android gives them ([SetupNetworkPort]); plain functions, so tests reach them. */
object SetupNetworkNames {
    private const val UNKNOWN_SSID = "<unknown ssid>"

    /** WifiInfo's SSID: quoted when it is UTF-8, "<unknown ssid>" when Android won't say; null for that and for none. */
    fun ssidOf(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t == UNKNOWN_SSID) return null
        return if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) t.substring(1, t.length - 1) else t
    }

    /** A network joined by hand that reaches the set-up address: used when its name is a set-up network's, or unreadable. */
    fun acceptsByHand(name: String?): Boolean = name == null || name.startsWith(OcbsSetup.SSID_PREFIX)
}
