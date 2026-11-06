package com.carlink.usb

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge 1.0.8 (API 6, [com.enigy.carlink2.bridge.BridgeContract.ACCESS_API_VERSION]): whether other apps the owner
 * approves may use the adapter, who they are, who asked, and whom Carlink handed the adapter to. Read from the
 * bridge's getAccess() ([parse]); an older bridge has none of it ([supported] false).
 */
data class BridgeAccess(
    /** The bridge's API, or -1 when no bridge answered. */
    val apiVersion: Int = -1,
    /** The bridge's versionName from its status line ("1.0.8"), or null. */
    val bridgeVersion: String? = null,
    val on: Boolean = false,
    /** The installed Carlink passes the bridge's pin by package; approvals count only while it does. */
    val carlinkVerified: Boolean = true,
    val approved: List<ApprovedApp> = emptyList(),
    /** Newest first. */
    val requests: List<AccessRequest> = emptyList(),
    val handedTo: Handover? = null,
    /** The package whose session is open on the bridge, Carlink's included; null when none is. */
    val holder: String? = null,
) {
    data class ApprovedApp(
        val packageName: String,
        val label: String,
        val sha256: String,
        val installer: String?,
        val approvedAt: Long,
        val lastUsedAt: Long,
        val sessions: Int,
    )

    data class AccessRequest(
        val packageName: String,
        val label: String,
        val sha256: String,
        val installer: String?,
        val firstAt: Long,
        val lastAt: Long,
        val count: Int,
        val keyChanged: Boolean,
    )

    data class Handover(
        val packageName: String,
        val since: Long,
    )

    /** The bridge has other apps' access at all. */
    val supported: Boolean get() = apiVersion >= ACCESS_API_VERSION

    /** The label of the app the adapter is handed to, falling back to its package. */
    val handedToLabel: String? get() = handedTo?.let { h -> labelOf(h.packageName) }

    fun labelOf(packageName: String): String = approved.find { it.packageName == packageName }?.label ?: packageName

    companion object {
        /** BridgeContract.ACCESS_API_VERSION, here so the model stays testable without the bridge's classes. */
        const val ACCESS_API_VERSION = 6

        /** getAccess()'s JSON, with the bridge's [apiVersion] and [bridgeVersion]; an unreadable document reads as off. */
        fun parse(
            json: String?,
            apiVersion: Int,
            bridgeVersion: String?,
        ): BridgeAccess {
            val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return BridgeAccess(apiVersion, bridgeVersion)
            return BridgeAccess(
                apiVersion = apiVersion,
                bridgeVersion = bridgeVersion,
                on = o.optBoolean("on"),
                carlinkVerified = o.optBoolean("carlinkVerified", true),
                approved = o.optJSONArray("approved").objects().map(::approvedApp),
                requests = o.optJSONArray("requests").objects().map(::accessRequest),
                handedTo = o.optJSONObject("handedTo")?.let { Handover(it.getString("package"), it.optLong("since")) },
                holder = o.stringOrNull("holder"),
            )
        }

        /** The bridge's versionName from its status line (`… v=1.0.8 …`), or null. */
        fun versionFromStatus(status: String?): String? = status?.let { VERSION.find(it)?.groupValues?.get(1) }

        private val VERSION = Regex("""\bv=(\S+)""")

        private fun approvedApp(o: JSONObject) =
            ApprovedApp(
                o.getString("package"),
                o.optString("label", o.getString("package")),
                o.optString("sha256"),
                o.stringOrNull("installer"),
                o.optLong("approvedAt"),
                o.optLong("lastUsedAt"),
                o.optInt("sessions"),
            )

        private fun accessRequest(o: JSONObject) =
            AccessRequest(
                o.getString("package"),
                o.optString("label", o.getString("package")),
                o.optString("sha256"),
                o.stringOrNull("installer"),
                o.optLong("firstAt"),
                o.optLong("lastAt"),
                o.optInt("count"),
                o.optBoolean("keyChanged"),
            )

        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

        private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
    }
}

/** Words and rules for Settings' USB bridge group and Bridge Configuration pane, apart from Compose so they are testable. */
object BridgeAccessText {
    const val SWITCH_TITLE = "Let other apps use the adapter"
    const val SWITCH_OFF = "Only Carlink can reach the adapter."
    const val SWITCH_ON = "Apps you approve can use the adapter when you hand it over."
    const val SWITCH_OLD_BRIDGE = "Needs USB bridge 1.0.8 or later."
    const val SWITCH_PARK = "Park to turn this on."

    const val RISK_TITLE = "Let other apps use the adapter?"
    const val RISK_TEXT =
        "An app you approve can control the adapter while you hand it over: its root console, its Apple authentication chip, " +
            "installing software on it, and touch input to your iPhone. Approve only apps you trust. An app gets the adapter " +
            "only when you hand it over, and Carlink can take it back at any time. Turning this off removes every approved app."

    const val NO_APPROVED = "No apps approved yet. Open the other app and let it connect; it shows up below."
    const val KEY_CHANGED = "Signed by a different key than the app you approved."
    const val TURN_OFF = "Turn off other apps' access"

    /** What the switch row says, and whether it can be touched. */
    data class SwitchState(
        val checked: Boolean,
        val subtitle: String,
        val enabled: Boolean,
    )

    /** Turning on is for parked; turning off works while driving. */
    fun switchState(
        access: BridgeAccess,
        driving: Boolean,
    ): SwitchState =
        when {
            !access.supported -> SwitchState(false, SWITCH_OLD_BRIDGE, enabled = false)
            access.on -> SwitchState(true, SWITCH_ON, enabled = true)
            driving -> SwitchState(false, "$SWITCH_OFF $SWITCH_PARK", enabled = false)
            else -> SwitchState(false, SWITCH_OFF, enabled = true)
        }

    /** The Bridge Configuration row's summary in the category list, e.g. "2 approved · Example has the adapter". */
    fun summary(access: BridgeAccess): String {
        val approved = "${access.approved.size} approved"
        val holder = access.handedToLabel?.let { "$it has the adapter" } ?: "Carlink has the adapter"
        val asking = if (access.requests.isEmpty()) "" else " · ${access.requests.size} asking"
        return "$approved · $holder$asking"
    }

    /** Who has the adapter, for the pane's status card: "Carlink", "<Label> since 10:42", or "Nobody". */
    fun holderLine(
        access: BridgeAccess,
        clock: (Long) -> String,
    ): String {
        access.handedTo?.let { return "${access.labelOf(it.packageName)} since ${clock(it.since)}" }
        return if (access.holder != null) "Carlink" else "Nobody"
    }

    /** The connecting screen's words while the adapter is handed over. */
    fun usingAdapter(label: String): String = "$label is using the adapter"

    fun handOverText(label: String): String = "CarPlay stops until $label is done or you take the adapter back."

    /** A certificate as the pane shows it: its first and last 4 bytes, "AA:BB:CC:DD…WW:XX:YY:ZZ". */
    fun shortCert(sha256: String): String {
        val bytes = sha256.split(':').filter { it.isNotEmpty() }
        if (bytes.size <= CERT_SHOWN * 2) return sha256
        return bytes.take(CERT_SHOWN).joinToString(":") + "…" + bytes.takeLast(CERT_SHOWN).joinToString(":")
    }

    fun installerText(installer: String?): String = if (installer == PLAY_STORE) "Play" else "sideloaded"

    private const val CERT_SHOWN = 4
    private const val PLAY_STORE = "com.android.vending"
}
