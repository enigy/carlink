package com.carlink.link

import com.carlink.ocbm.Ocbm

/**
 * M2 (the owner, 23 Nov: "the adapter can start CarPlay by itself"), Carlink's side: in Wi-Fi mode the adapter may already
 * run the phone's CarPlay session, its video parked, when Carlink finds it on the hotspot. Carlink then attaches to that
 * session (notes/carlink.md, "M2 attach"), and in Wi-Fi mode it keeps looking for the adapter for as long as it isn't
 * linked, since the hotspot can come up at any point of the drive (addendum 3: no hotspot automation is assumed).
 */
object WifiAttach {
    /** What a start does once the box's state at SUBSCRIBE is known ([com.carlink.ocbm.HelloReplay]). */
    enum class Action {
        /** A running session over Wi-Fi: ask for the screen and a keyframe, and show the phone as connected. */
        ATTACH,

        /** No session running: the usual wait for the phone. */
        WAIT_FOR_PHONE,

        /** Over USB, or the last host's session that ended: what Carlink always did. */
        AS_BEFORE,
    }

    /** [live] and [mode]: the replay hold's decision ([com.carlink.ocbm.HelloReplay.Release]); [overWifi]: this session's road. */
    fun onDecided(
        live: Boolean,
        mode: Byte?,
        overWifi: Boolean,
    ): Action =
        when {
            !overWifi -> Action.AS_BEFORE
            live && mode != null && mode != Ocbm.PM_NONE -> Action.ATTACH
            else -> Action.WAIT_FOR_PHONE
        }

    /** Between two Wi-Fi searches that found nothing, while Wi-Fi mode lasts. */
    const val SEARCH_GAP_MS = 2_000L

    /**
     * With the bridge installed, how long the Wi-Fi searches may find nothing before a start takes USB through it: long
     * enough for a hotspot turned on as CarPlay or Bluetooth starts, short enough that a hotspot that never comes
     * leaves the drive on USB, not on nothing.
     */
    const val USB_FALLBACK_MS = 45_000L

    /**
     * Whether Carlink may speak on USB to the adapter at all: always outside Wi-Fi mode; in Wi-Fi mode only once the Wi-Fi
     * searches have found nothing for [USB_FALLBACK_MS]. A USB host takes the box from a Wi-Fi host at once (the adapter
     * side, 24 Nov night), so a USB frame while the Wi-Fi link is healthy would cut Carlink's own link; a link taken over
     * Wi-Fi resets [searchingSinceMs] to 0, so this is false for as long as that link lasts. Every way to USB goes through
     * here: the start's routes, the fall-back after a refused Wi-Fi HELLO, and the quiet search's hand-over.
     */
    fun mayTryUsb(
        wifiActive: Boolean,
        searchingSinceMs: Long,
        nowMs: Long,
    ): Boolean = !wifiActive || usbFallbackDue(searchingSinceMs, nowMs)

    /** Whether a start may give Wi-Fi up for the bridge: the searches have found nothing since [searchingSinceMs]. */
    fun usbFallbackDue(
        searchingSinceMs: Long,
        nowMs: Long,
    ): Boolean = searchingSinceMs > 0 && nowMs - searchingSinceMs >= USB_FALLBACK_MS

    /**
     * Whether a USB detach ends the session: always outside Wi-Fi mode, as before; in Wi-Fi mode only when USB is the road
     * in use ([overUsb]). A Wi-Fi session, or a start still searching on Wi-Fi, is the Wi-Fi link's to end.
     */
    fun detachEndsSession(
        wifiChosen: Boolean,
        overUsb: Boolean,
    ): Boolean = !wifiChosen || overUsb

    /** Whether to look again: Wi-Fi mode still on, nothing linked, nothing that stops Carlink (closing, display off). */
    fun keepLooking(
        wifiActive: Boolean,
        disconnected: Boolean,
        stopped: Boolean,
    ): Boolean = wifiActive && disconnected && !stopped
}
