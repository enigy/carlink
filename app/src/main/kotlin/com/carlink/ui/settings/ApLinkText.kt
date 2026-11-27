package com.carlink.ui.settings

import com.carlink.link.ApBand
import com.carlink.link.ConnectOrder
import com.carlink.link.SetupOverNetwork
import com.carlink.link.WifiBoxSearch
import com.carlink.link.WifiLinkSettings
import com.carlink.link.WifiLinkSetup
import com.carlink.ocbm.WifiLinkInfo

/**
 * The AP link's words in Settings › Adapter Config › Adapter connection ([WifiLinkGroup]): since the plan's ADDENDUM 24
 * Wi-Fi means the head unit joins the adapter's own Wi-Fi. The words shared with the other choices, and the hotspot
 * link's, are [WifiLinkText]'s.
 */
internal object ApLinkText {
    const val TITLE = "The adapter's Wi-Fi"

    /** Said plainly (ADDENDUM 24): the head unit's internet doesn't come over this network. */
    const val NO_INTERNET = "The head unit has no internet over this network."
    const val BODY = "This head unit joins the adapter's own Wi-Fi network, the one your iPhone uses for CarPlay. $NO_INTERNET"
    const val SET_UP_TITLE = "Set up the adapter's Wi-Fi"
    const val SET_UP_AGAIN_TITLE = "Set up again"
    const val SET_UP_ACTION = "Set up"
    const val SETTING_UP = "Setting up..."
    const val BAND_TITLE = "Adapter Wi-Fi band"
    const val BAND_CONFIRM = "Move"
    const val JOIN_TITLE = "Join the adapter's Wi-Fi"
    const val NETWORK_LABEL = "Network"
    const val PASSWORD_LABEL = "Password"

    /** The steps to join it, short and in order: the manual way, which always works (ADDENDUM 24 item 7). */
    val JOIN_STEPS =
        listOf(
            "1. Open the car's Wi-Fi settings.",
            "2. Join this network with this password.",
            "3. Come back to Carlink: it finds the adapter there by itself.",
        )
    const val OPEN_WIFI_SETTINGS = "Open Wi-Fi settings"
    const val NO_WIFI_SETTINGS = "Carlink can't open the Wi-Fi settings here. Open them from the car's own Settings."
    const val PASSWORD_WHILE_PARKED = "Shown while parked."
    const val PASSWORD_NOT_KEPT = "Not kept on this head unit. Tap Set up again to see it."
    const val PASSWORD_NONE = "None: the network is open."
    const val NOT_NAMED = "The adapter didn't name its Wi-Fi. Update its firmware, then tap Set up again."
    const val KEY_UNREADABLE = "This head unit can no longer read its Wi-Fi key. Tap Set up again."
    const val HOTSPOT_LEFT = "Set up for the iPhone's hotspot by an earlier test version. Tap Set up for the adapter's own Wi-Fi."
    const val REMOVE_SUBTITLE = "The adapter's Wi-Fi goes back to how it was, and Carlink uses USB."
    private const val UNKNOWN_STATE = "The adapter's Wi-Fi state is new to this Carlink."

    /** What stands in the set-up's way on the session's side: none, no session, or an adapter without the link. */
    fun blocked(wifiLinkSupported: Boolean?): WifiLinkSetup.Sent? =
        when (wifiLinkSupported) {
            null -> WifiLinkSetup.Sent.NO_SESSION
            false -> WifiLinkSetup.Sent.NOT_SUPPORTED
            true -> null
        }

    /** The set-up row's line before a set-up: what stands in the way, or what it does. */
    fun readyLine(
        blocked: WifiLinkSetup.Sent?,
        parked: Boolean,
    ): String =
        when {
            blocked != null -> sentLine(blocked, null)
            !parked -> "While parked."
            else -> "Sends this head unit's key, and shows the adapter's Wi-Fi name and password."
        }

    /**
     * The set-up row's line after a set-up. [info]: what the adapter says of its Wi-Fi; one that names no mode is from
     * before ADDENDUM 24, and refuses this set-up. Otherwise a refusal may pass: the adapter refuses while it can't yet
     * say its Wi-Fi's name and password (notes/ocbm.md, ADDENDUM 24), so a try a moment later can work.
     */
    fun sentLine(
        sent: WifiLinkSetup.Sent,
        info: WifiLinkInfo?,
    ): String =
        when (sent) {
            WifiLinkSetup.Sent.SENT -> "Set up. Now join the adapter's Wi-Fi in the car's Wi-Fi settings."
            WifiLinkSetup.Sent.SENT_RECONNECTING -> "Set up. If the adapter restarts its Wi-Fi, Carlink reconnects by itself."
            WifiLinkSetup.Sent.REFUSED ->
                if (info != null && info.mode == null) {
                    "The adapter refused: its firmware predates its own Wi-Fi link. Update it first."
                } else {
                    "The adapter refused. Try again in a moment."
                }
            WifiLinkSetup.Sent.BUSY -> "Already setting up."
            else -> WifiLinkText.sentLine(sent, "")
        }

    /**
     * Under the band choice: what 2.4 GHz is for, or, when the adapter runs another band than the one picked (MGMT_INFO's
     * `ap_band`), that the next set-up moves it.
     */
    fun bandLine(
        s: WifiLinkSettings,
        info: WifiLinkInfo?,
    ): String {
        val running = ApBand.ofCode(info?.apBand)
        return if (s.apLink && running != null && running != s.band) {
            "The adapter's Wi-Fi is on ${running.label} now. Tap Set up again to move it to ${s.band.label}."
        } else {
            "2.4 GHz works with most head units. The adapter takes the band at the set-up."
        }
    }

    /** Whether a set-up of [s] now would move the adapter's Wi-Fi off the band it runs ([info]): then it asks first. */
    fun movesBand(
        s: WifiLinkSettings,
        info: WifiLinkInfo?,
    ): Boolean = ApBand.ofCode(info?.apBand)?.let { it != s.band } == true

    fun bandDialogTitle(band: ApBand): String = "Move the adapter's Wi-Fi to ${band.label}?"

    fun bandDialogText(band: ApBand): String =
        "The adapter restarts its Wi-Fi on ${band.label}, and your iPhone reconnects to it. This head unit has to see " +
            "${band.label} networks too, or Carlink reaches the adapter only over USB."

    /**
     * The lines under the set-up: what the adapter says of its Wi-Fi ([info], null with no session), a key this head unit
     * can't read, a hotspot set-up left from an earlier build, why the last look found nothing, and the turn icons a missing
     * bridge costs.
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
            if (s.keyUnreadable) add(KEY_UNREADABLE)
            if (s.setUp && !s.apLink) add(HOTSPOT_LEFT)
            if (s.apLink && linkedOver != ConnectOrder.Route.WIFI) miss?.let { add(missLine(it)) }
            if (!bridgeInstalled) add(WifiLinkText.BRIDGE_MISSING)
        }

    /** What the adapter says of its Wi-Fi link (MGMT_INFO), in the AP link's words. */
    fun infoLine(info: WifiLinkInfo): String =
        when {
            info.mode == null -> "This adapter's firmware predates its own Wi-Fi link. Update it first."
            info.mode == WifiLinkInfo.Mode.OFF -> "The adapter has no Wi-Fi link yet."
            info.mode == WifiLinkInfo.Mode.HOTSPOT -> "The adapter is set up for the iPhone's hotspot. Tap Set up to move it to its own Wi-Fi."
            info.mode != WifiLinkInfo.Mode.AP -> UNKNOWN_STATE
            info.state == WifiLinkInfo.State.UP -> "The adapter's Wi-Fi is up${radioOf(info)}."
            info.state == WifiLinkInfo.State.LINKED -> "Carlink is connected over the adapter's Wi-Fi${radioOf(info)}."
            info.state == WifiLinkInfo.State.OFF -> "The adapter's Wi-Fi isn't up yet."
            else -> UNKNOWN_STATE
        }

    /** ": 2.4 GHz, channel 6", as much of it as the adapter said. */
    private fun radioOf(info: WifiLinkInfo): String {
        val parts = listOfNotNull(ApBand.ofCode(info.apBand)?.label, info.apChannel.takeIf { it > 0 }?.let { "channel $it" })
        return if (parts.isEmpty()) "" else ": ${parts.joinToString(", ")}"
    }

    /** Why the last look on Wi-Fi found nothing. */
    fun missLine(why: WifiBoxSearch.Why): String =
        when (why) {
            WifiBoxSearch.Why.NO_WIFI -> "Last look: this head unit was on no Wi-Fi network. Join the adapter's Wi-Fi in the car's Wi-Fi settings."
            WifiBoxSearch.Why.NO_ANSWER -> "Last look: the adapter didn't answer. Check that the head unit is on the adapter's Wi-Fi."
            WifiBoxSearch.Why.KEY_REFUSED -> "Last look: the adapter refused this head unit's key. Tap Set up again."
        }

    /** The set-up over the adapter's set-up network (M3): where the last run stands. */
    fun setupNetworkLine(
        phase: SetupOverNetwork.Phase,
        result: SetupOverNetwork.Result?,
    ): String =
        when {
            phase == SetupOverNetwork.Phase.EXCHANGING -> "Setting up the adapter..."
            // A run starts by clearing its result, so this is the last run's.
            result == SetupOverNetwork.Result.SENT -> "Set up. Now join the adapter's Wi-Fi below."
            else -> WifiLinkText.setupNetworkLine(phase, result, "")
        }

    fun removeDialogText(
        s: WifiLinkSettings,
        bridgeInstalled: Boolean,
    ): String =
        "Carlink forgets the adapter's Wi-Fi and connects over USB, and the adapter's Wi-Fi goes back to how it was." +
            (if (bridgeInstalled) "" else " This head unit has no USB bridge, so Carlink can't reach the adapter until Wi-Fi is set up again.") +
            if (s.apName.isEmpty()) "" else " You can also forget \"${s.apName}\" in the car's Wi-Fi settings."
}
