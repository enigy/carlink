package com.carlink.link

/**
 * Which ways to the adapter a start tries, in order (the Wi-Fi link plan, Carlink side, and its addendum 1):
 *
 * - USB chosen: the bridge, as Carlink always has; without it the start stops at "install the bridge".
 * - Wi-Fi chosen and set up: Wi-Fi, then the bridge only when it is installed.
 * - Wi-Fi chosen, not set up: the bridge when it is installed (in M1 the set-up needs a session, and the bridge is how a
 *   head unit without the link gets one); without it nothing, and the start says how to set up, never "install the
 *   bridge".
 *
 * New ways (a USB transport of Carlink's own, the adapter's set-up network: notes/carlink.md) join as new [Route]s here.
 */
object ConnectOrder {
    enum class Route { WIFI, USB_BRIDGE }

    fun routes(
        settings: WifiLinkSettings,
        bridgeInstalled: Boolean,
    ): List<Route> =
        when {
            !settings.wifiChosen -> listOf(Route.USB_BRIDGE)
            settings.setUp && bridgeInstalled -> listOf(Route.WIFI, Route.USB_BRIDGE)
            settings.setUp -> listOf(Route.WIFI)
            bridgeInstalled -> listOf(Route.USB_BRIDGE)
            else -> emptyList()
        }
}
