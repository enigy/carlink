package com.carlink.ui.settings

import com.carlink.link.AdapterLink
import com.carlink.link.ConnectOrder
import com.carlink.link.SetupOverNetwork
import com.carlink.link.WifiBoxSearch
import com.carlink.link.WifiLinkSettings
import com.carlink.link.WifiLinkSetup
import com.carlink.ocbm.OcbsSetup
import com.carlink.ocbm.WifiLinkInfo

/** The words of Settings › Adapter Config and about › Adapter connection ([WifiLinkGroup]). */
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

    /**
     * What stands in a send's way on the session's side: none, no session, an adapter without the link, or one whose radio
     * can't join a hotspot ([info] "unsupported").
     */
    fun blocked(
        wifiLinkSupported: Boolean?,
        info: WifiLinkInfo? = null,
    ): WifiLinkSetup.Sent? =
        when {
            wifiLinkSupported == null -> WifiLinkSetup.Sent.NO_SESSION
            !wifiLinkSupported -> WifiLinkSetup.Sent.NOT_SUPPORTED
            info?.state == WifiLinkInfo.State.UNSUPPORTED -> WifiLinkSetup.Sent.NO_STATION
            else -> null
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

    const val APOSTROPHE_NOTE = "An apostrophe is written the way an iPhone's name has it (\u2019)."

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
            WifiLinkSetup.Sent.KEY_STORE -> "This head unit couldn't read or keep its Wi-Fi key just now. Try again."
            WifiLinkSetup.Sent.NO_STATION -> "This adapter's radio can't join a hotspot beside its own Wi-Fi, so it has no Wi-Fi link. Use USB."
        }

    const val SETUP_NETWORK_TITLE = "Set up over the adapter's Wi-Fi"

    /** Which adapters offer the set-up network (the adapter side, 24 Nov night): a never-used one, or one reset by a stick. */
    const val USED_ADAPTER =
        "An adapter that has been used before doesn't offer it; to set one up again, power it up with a USB stick in it " +
            "that holds an empty file named OCBM_WIFI_RESET."

    /**
     * The set-up over the adapter's own set-up network (M3): for a head unit with no other way to the adapter. [phase]
     * and [result]: where the last run stands.
     */
    fun setupNetworkLine(
        phase: SetupOverNetwork.Phase,
        result: SetupOverNetwork.Result?,
        ssid: String,
    ): String =
        when {
            phase == SetupOverNetwork.Phase.JOINING -> "Joining the adapter's set-up Wi-Fi. Pick ${OcbsSetup.SSID_PREFIX}... if Android asks."
            phase == SetupOverNetwork.Phase.EXCHANGING -> "Sending the hotspot to the adapter..."
            phase == SetupOverNetwork.Phase.CONFIRMING -> "Compare the code with the adapter's Bluetooth name."
            result == null -> "No other way to the adapter? A new adapter, never used, offers its own Wi-Fi, ${OcbsSetup.SSID_PREFIX}..., for 10 minutes after power-up."
            else -> setupNetworkResult(result, ssid)
        }

    fun setupNetworkResult(
        r: SetupOverNetwork.Result,
        ssid: String,
    ): String =
        when (r) {
            SetupOverNetwork.Result.SENT -> "Sent. The adapter joins \"$ssid\", and Carlink finds it there."
            SetupOverNetwork.Result.NOT_JOINED ->
                "No set-up Wi-Fi was joined. If the adapter offers ${OcbsSetup.SSID_PREFIX}..., join it in the car's Wi-Fi settings " +
                    "(password ${OcbsSetup.PASSPHRASE}), then tap Set up again. $USED_ADAPTER"
            SetupOverNetwork.Result.NO_ADAPTER -> "No adapter answered on a set-up Wi-Fi. $USED_ADAPTER"
            SetupOverNetwork.Result.NOT_SUPPORTED -> sentLine(WifiLinkSetup.Sent.NOT_SUPPORTED, ssid)
            SetupOverNetwork.Result.REFUSED -> sentLine(WifiLinkSetup.Sent.REFUSED, ssid)
            SetupOverNetwork.Result.NO_ANSWER -> sentLine(WifiLinkSetup.Sent.NO_ANSWER, ssid)
            SetupOverNetwork.Result.BUSY -> sentLine(WifiLinkSetup.Sent.BUSY, ssid)
            SetupOverNetwork.Result.KEY_STORE -> sentLine(WifiLinkSetup.Sent.KEY_STORE, ssid)
            SetupOverNetwork.Result.NOT_CONFIRMED -> "No answer in time, so nothing was sent. $SET_UP_AGAIN"
            SetupOverNetwork.Result.NO_MATCH -> "Nothing was sent: another adapter or network answered. Try again closer to your adapter."
            SetupOverNetwork.Result.LINK_LOST -> "The link to the adapter dropped, so nothing was sent. $SET_UP_AGAIN"
        }

    private const val SET_UP_AGAIN = "Tap Set up again within 10 minutes of the adapter's power-up."

    /** The set-up's one question (the plan's ADDENDUM 5): the code, shown large under [codeText], and its two answers. */
    const val CODE_TITLE = "Is this your adapter?"
    const val CODE_MATCH = "They match"
    const val CODE_NO_MATCH = "They don't match"

    /**
     * Where the driver checks the code, that a phone's list can lag, and, when Android won't name the network Carlink joined
     * ([networkName] null), the car's Wi-Fi settings too: one question, never a second.
     */
    fun codeText(
        code: String,
        networkName: String?,
    ): String =
        "The adapter's Bluetooth name now reads \"${OcbsSetup.CODE_NAME_PREFIX}$code\" in your iPhone's Bluetooth list, or the car's. " +
            "Your iPhone's list can take a moment to show the new name." +
            if (networkName != null) {
                ""
            } else {
                " Android won't let Carlink read the Wi-Fi network's name here, so check too that the car's Wi-Fi settings show " +
                    "${OcbsSetup.SSID_PREFIX}... as connected."
            }

    /** What the adapter says of its Wi-Fi link (MGMT_INFO). */
    fun infoLine(info: WifiLinkInfo): String =
        when (info.state) {
            WifiLinkInfo.State.OFF -> "The adapter has no Wi-Fi link."
            WifiLinkInfo.State.JOINING -> "The adapter is looking for the hotspot."
            WifiLinkInfo.State.JOINED -> "The adapter is on the hotspot."
            WifiLinkInfo.State.LINKED -> "The adapter is connected to Carlink over the hotspot."
            WifiLinkInfo.State.UNSUPPORTED -> "No Wi-Fi link on this adapter's radio: it can't join a hotspot beside its own Wi-Fi."
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
