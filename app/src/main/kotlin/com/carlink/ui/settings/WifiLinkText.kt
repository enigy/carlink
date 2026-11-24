package com.carlink.ui.settings

import com.carlink.link.AdapterLink
import com.carlink.link.ConnectOrder
import com.carlink.link.WifiBoxSearch
import com.carlink.link.WifiLinkSettings
import com.carlink.link.WifiLinkSetup
import com.carlink.ocbm.WifiLinkInfo

/** The words of Settings › Adapter and about › Adapter connection ([WifiLinkGroup]). */
internal object WifiLinkText {
    const val TITLE = "Adapter connection"
    const val HOTSPOT_TITLE = "iPhone's hotspot"
    const val HOTSPOT_BODY = "The adapter joins your iPhone's Personal Hotspot and Carlink reaches it there. Only a hash of the password is sent."
    const val BRIDGE_MISSING = "Without the USB bridge, the cluster and HUD show no turn icons."
    const val KEY_UNREADABLE = "This head unit can no longer read its Wi-Fi key. Send the hotspot again."
    const val REMOVE_TITLE = "Remove the Wi-Fi link"
    const val REMOVE_SUBTITLE = "The adapter stops joining the hotspot, and Carlink uses USB."
    const val REMOVE_DIALOG_TITLE = "Remove the Wi-Fi link?"

    /** Under the choice: how Carlink reaches the adapter now, or will. */
    fun connectionLine(
        s: WifiLinkSettings,
        linkedOver: ConnectOrder.Route?,
        bridgeInstalled: Boolean = true,
    ): String =
        when {
            s.link == AdapterLink.USB_DIRECT -> "Carlink opens the adapter itself. Android asks once."
            s.link == AdapterLink.USB_BRIDGE && bridgeInstalled -> "Through the Carlink USB bridge app."
            s.link == AdapterLink.USB_BRIDGE -> "The bridge app isn't installed: Carlink asks Android for the adapter itself."
            linkedOver == ConnectOrder.Route.WIFI -> "Connected over the iPhone's hotspot."
            !s.setUp -> "Send the hotspot's name and password to the adapter below."
            linkedOver != null -> "Over the hotspot from the next connection. Reset connection applies it now."
            else -> "Over the iPhone's hotspot \"${s.ssid}\"."
        }

    /** What stands in a send's way on the session's side: none, no session, or an adapter without the link. */
    fun blocked(wifiLinkSupported: Boolean?): WifiLinkSetup.Sent? =
        when (wifiLinkSupported) {
            null -> WifiLinkSetup.Sent.NO_SESSION
            false -> WifiLinkSetup.Sent.NOT_SUPPORTED
            true -> null
        }

    /**
     * The lines under Send: what the adapter says of its Wi-Fi ([info], null with no session), a channel mismatch, a key
     * this head unit can't read, why the last look found nothing, and the turn icons a missing bridge costs.
     */
    fun stateLines(
        s: WifiLinkSettings,
        info: WifiLinkInfo?,
        miss: WifiBoxSearch.Why?,
        linkedOver: ConnectOrder.Route?,
        bridgeInstalled: Boolean,
    ): List<String> =
        buildList {
            info?.let { add(infoLine(it)) }
            info?.let(::channelLine)?.let(::add)
            if (s.keyUnreadable) add(KEY_UNREADABLE)
            if (s.setUp && linkedOver != ConnectOrder.Route.WIFI) miss?.let { add(missLine(it)) }
            if (!bridgeInstalled) add(BRIDGE_MISSING)
        }

    /** The Send row's line before a send: what stands in the way, or that it is ready. */
    fun readyLine(
        blocked: WifiLinkSetup.Sent?,
        parked: Boolean,
        problem: String?,
    ): String =
        when {
            blocked != null -> sentLine(blocked, "")
            !parked -> "While parked."
            problem != null -> problem
            else -> "Ready to send."
        }

    /** The Send row's line after a send. */
    fun sentLine(
        sent: WifiLinkSetup.Sent,
        ssid: String,
    ): String =
        when (sent) {
            WifiLinkSetup.Sent.SENT -> "Sent. The adapter joins \"$ssid\"."
            WifiLinkSetup.Sent.SENT_RECONNECTING -> "Sent. The adapter joins \"$ssid\", and Carlink reconnects there."
            WifiLinkSetup.Sent.NO_SESSION -> "Connect to the adapter first."
            WifiLinkSetup.Sent.NOT_SUPPORTED -> "This adapter's firmware has no Wi-Fi link. Update it first."
            WifiLinkSetup.Sent.REFUSED -> "The adapter refused these settings."
            WifiLinkSetup.Sent.NO_ANSWER -> "The adapter didn't answer. Try again."
            WifiLinkSetup.Sent.BUSY -> "Already sending."
        }

    /** What the adapter says of its Wi-Fi link (MGMT_INFO). */
    fun infoLine(info: WifiLinkInfo): String =
        when (info.state) {
            WifiLinkInfo.State.OFF -> "The adapter has no Wi-Fi link."
            WifiLinkInfo.State.JOINING -> "The adapter is looking for the hotspot."
            WifiLinkInfo.State.JOINED -> "The adapter is on the hotspot."
            WifiLinkInfo.State.LINKED -> "The adapter is connected to Carlink over the hotspot."
            WifiLinkInfo.State.UNKNOWN -> "The adapter's Wi-Fi state is new to this Carlink."
        }

    /** One line when the hotspot and the adapter's own Wi-Fi are on different channels; null when they aren't. */
    fun channelLine(info: WifiLinkInfo): String? =
        if (!info.channelsDiffer) {
            null
        } else {
            "The hotspot is on channel ${info.channel}, the adapter's own Wi-Fi on ${info.apChannel}. One radio serves both, so CarPlay may slow down."
        }

    /** Why the last look on Wi-Fi found nothing. */
    fun missLine(why: WifiBoxSearch.Why): String =
        when (why) {
            WifiBoxSearch.Why.NO_WIFI -> "Last look: this head unit was on no Wi-Fi network."
            WifiBoxSearch.Why.NO_ANSWER -> "Last look: the adapter didn't answer on Wi-Fi."
            WifiBoxSearch.Why.KEY_REFUSED -> "Last look: the adapter refused this head unit's key. Send the hotspot again."
        }

    fun removedLine(r: WifiLinkSetup.Removed): String =
        when (r) {
            WifiLinkSetup.Removed.REMOVED -> "Removed. Carlink uses USB."
            WifiLinkSetup.Removed.HERE_ONLY -> "Removed here. The adapter keeps its Wi-Fi link until it is removed while connected."
            WifiLinkSetup.Removed.ADAPTER_KEPT -> "Removed here, but the adapter kept its own."
        }

    fun removeDialogText(
        ssid: String,
        bridgeInstalled: Boolean,
    ): String =
        "The adapter stops joining \"$ssid\", and Carlink connects over USB." +
            if (bridgeInstalled) "" else " This head unit has no USB bridge, so Carlink can't reach the adapter until Wi-Fi is set up again."
}
