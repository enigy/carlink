package com.carlink.link

/**
 * Which ways to the adapter a start tries, in order (the Wi-Fi link plan, Carlink side, and its addenda 1 and 3):
 *
 * - USB (bridge app): the bridge, as Carlink always has; with no bridge installed, Android asked for the adapter directly.
 * - USB (direct): Android asked for the adapter directly, bridge or no bridge.
 * - Wi-Fi chosen and set up: Wi-Fi, then the bridge only when it is installed.
 * - Wi-Fi chosen, not set up: a session to set it up through, the bridge's or a direct one.
 *
 * The adapter's set-up network (notes/carlink.md, M3) would join as one more [Route] here.
 */
object ConnectOrder {
    enum class Route { WIFI, USB_DIRECT, USB_BRIDGE }

    fun routes(
        settings: WifiLinkSettings,
        bridgeInstalled: Boolean,
    ): List<Route> {
        val usb = if (bridgeInstalled) Route.USB_BRIDGE else Route.USB_DIRECT
        return when {
            settings.link == AdapterLink.USB_DIRECT -> listOf(Route.USB_DIRECT)
            settings.link == AdapterLink.USB_BRIDGE -> listOf(usb)
            settings.setUp && bridgeInstalled -> listOf(Route.WIFI, Route.USB_BRIDGE)
            settings.setUp -> listOf(Route.WIFI)
            else -> listOf(usb)
        }
    }
}
