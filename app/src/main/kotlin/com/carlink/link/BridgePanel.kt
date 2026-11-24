package com.carlink.link

/** Where Carlink's own USB attempt stands (USB direct, or USB bridge with no bridge installed). */
enum class DirectUsb {
    /** Nothing tried yet: no adapter listed since the start. */
    IDLE,

    /** An adapter is listed and Android has been asked for it. */
    WAITING,

    /** Android gave Carlink the adapter. */
    GRANTED,

    /** Android said no, or never answered (the GM head unit). */
    REFUSED,

    /** A stock Carlinkit adapter, or the stick a stock one shares: its conversion goes through the bridge only. */
    STOCK,
}

/** Whether the connecting screen shows the bridge's install panel (the reviewer's 24 Nov finding 2). */
object BridgePanel {
    fun shows(
        installed: Boolean,
        link: AdapterLink,
        direct: DirectUsb,
    ): Boolean =
        !installed &&
            when (link) {
                // As before the direct path came back: at once, unless Android is being asked or gave the adapter.
                AdapterLink.USB_BRIDGE -> direct != DirectUsb.WAITING && direct != DirectUsb.GRANTED
                // Carlink opens the adapter itself; only a stock adapter's conversion needs the bridge.
                AdapterLink.USB_DIRECT -> direct == DirectUsb.STOCK
                // Once Wi-Fi is chosen nothing asks for the bridge (addendum 1).
                AdapterLink.WIFI -> false
            }
}
