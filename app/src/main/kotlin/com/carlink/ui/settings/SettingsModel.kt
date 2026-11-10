package com.carlink.ui.settings

import com.carlink.CarlinkManager
import com.carlink.ocbm.Ocbm
import com.carlink.protocol.PhoneType

/** The manager's phone-side fields at one instant; the settings screen polls them while open. */
data class PhoneSnapshot(
    val phoneType: PhoneType? = null,
    /** 1 = wireless, 0 = USB, -1 = the adapter did not say. */
    val wifi: Int = -1,
    val btMac: String? = null,
    /**
     * False once the adapter says no phone is on it (`SEV_PHONE_ABSENT`). The session's [phoneType] outlives a
     * wireless phone that dropped while the adapter waits for one (2 Nov bench: "iPhone · Wired CarPlay" stayed).
     */
    val present: Boolean = true,
)

/** The phone the box reports as connected right now, named for the Phones pane. */
data class ConnectedPhone(
    val name: String,
    val kind: String,
    val wireless: Boolean,
    /** The known-device record behind a wireless phone; null for USB or an unknown MAC. */
    val device: CarlinkManager.DeviceInfo?,
)

/** The box's own status (CT_PROJ_MODE + CT_BOX_HEALTH) as the Adapter pane shows it. */
data class BoxSnapshot(
    val adapterConnected: Boolean = false,
    val projectionMode: Byte = Ocbm.PM_NONE,
    val health: Int = 0,
    val healthKnown: Boolean = false,
    /** The raw "WIRED_CP · HCI|SSP|..." line, kept for bug reports. */
    val raw: String = "",
    /**
     * A one-file install keeps stock CarPlay beside OCBM (trial or confirmed), and with both on it the flash is full by
     * design: about 1.2 MB free, under the 2 MB the adapter's rootfs-ok bit wants. Bench 11-03: a healthy trial read
     * "check status" and "— Firmware" until the install finished.
     */
    val stockKept: Boolean = false,
)

/** One plain-word health chip; [ok] is null until the box has reported its health. */
data class HealthItem(
    val label: String,
    val ok: Boolean?,
)

/**
 * The words the merged settings screen shows for live state: category summaries, the connected
 * phone's name, the adapter's health in plain language. Pure, so every rule is a JVM test.
 */
object SettingsModel {
    /** Bluetooth radio, Wi-Fi hotspot and a healthy root filesystem: what "all systems OK" requires. */
    private const val CORE_HEALTH = Ocbm.BH_HCI_PRESENT or Ocbm.BH_WLAN_AP or Ocbm.BH_ROOTFS_OK

    /**
     * The connected phone, or null. `wifi == 1` is the only explicit wireless marker; an active type
     * with the field missing is USB, because the adapter always sends `wifi=1` for wireless.
     */
    fun connectedPhone(
        phone: PhoneSnapshot,
        devices: List<CarlinkManager.DeviceInfo>,
    ): ConnectedPhone? {
        val type = phone.phoneType?.takeIf { phone.present } ?: return null
        val wireless = phone.wifi == 1 || type == PhoneType.CARPLAY_WIRELESS
        val device = if (wireless) devices.firstOrNull { it.btMac == phone.btMac } else null
        val name =
            when {
                device != null -> device.name
                wireless -> "Wireless phone"
                isCarPlay(type) -> "iPhone"
                else -> "Phone"
            }
        return ConnectedPhone(name, kindLabel(type, wireless), wireless, device)
    }

    fun kindLabel(
        type: PhoneType,
        wireless: Boolean,
    ): String {
        val family =
            when {
                isCarPlay(type) -> "CarPlay"
                type == PhoneType.ANDROID_AUTO -> "Android Auto"
                else -> "projection"
            }
        return if (wireless) "Wireless $family" else "Wired $family"
    }

    fun phonesSummary(
        connected: ConnectedPhone?,
        knownCount: Int,
    ): String {
        val head = connected?.let { "${it.name} connected" } ?: "No phone connected"
        return "$head · $knownCount known"
    }

    /** Display: the mode, the split layout when one is on, and the loading screen. */
    fun displaySummary(
        mode: DisplayMode,
        logo: BackgroundLogoConfig,
        layout: PaneLayout = PaneLayout.FULL,
    ): String =
        mode.label + (if (layout.split) " · ${layout.label}" else "") + " · " +
            if (logo == BackgroundLogoConfig.NONE) "plain loading screen" else "${logo.brandName} loading screen"

    fun callsSummary(
        mic: CallMicConfig,
        buffer: CallBufferConfig,
    ): String {
        val micText = if (mic == CallMicConfig.AUTOMATIC) "Automatic mic" else mic.label
        return "$micText · ${buffer.label} buffer"
    }

    /** Cluster: directions on or off, and whether lane guidance is switched on. */
    fun clusterSummary(
        on: Boolean,
        lanes: Boolean = false,
    ): String = (if (on) "Directions on" else "Directions off") + if (lanes) " · Lane guidance on" else ""

    /** Troubleshoot: the fixes, and the logs that now live beside them; says when a drive log is running, or is on and writes nothing. */
    fun troubleshootSummary(
        driveLog: Boolean,
        driveLogTrouble: Boolean = false,
    ): String =
        when {
            !driveLog -> "Fixes and logs"
            driveLogTrouble -> "Fixes and logs · Drive log not recording"
            else -> "Fixes and logs · Recording drive log"
        }

    fun adapterSummary(
        adapterName: String,
        box: BoxSnapshot,
    ): String =
        when {
            !box.adapterConnected -> "$adapterName · not connected"
            !box.healthKnown -> "$adapterName · connected"
            coreHealthy(box) -> "$adapterName · all systems OK"
            else -> "$adapterName · check status"
        }

    fun coreHealthy(box: BoxSnapshot): Boolean {
        val core = if (box.stockKept) CORE_HEALTH and Ocbm.BH_ROOTFS_OK.inv() else CORE_HEALTH
        return box.healthKnown && box.health and core == core
    }

    /**
     * Bluetooth, Wi-Fi, CarPlay and Firmware, each lit by the CT_BOX_HEALTH bits behind it. While stock is kept the
     * rootfs bit is off by design ([BoxSnapshot.stockKept]), so Firmware is lit by the adapter answering.
     */
    fun healthItems(box: BoxSnapshot): List<HealthItem> {
        fun any(mask: Int): Boolean? = if (box.healthKnown && box.adapterConnected) box.health and mask != 0 else null
        val firmware = if (box.stockKept) any(Ocbm.BH_ROOTFS_OK)?.let { true } else any(Ocbm.BH_ROOTFS_OK)
        return listOf(
            HealthItem("Bluetooth", any(Ocbm.BH_HCI_PRESENT)),
            HealthItem("Wi-Fi", any(Ocbm.BH_WLAN_AP)),
            HealthItem("CarPlay", any(Ocbm.BH_IAP2D or Ocbm.BH_AIRPLAYD)),
            HealthItem("Firmware", firmware),
        )
    }

    fun adapterMode(box: BoxSnapshot): String {
        if (!box.adapterConnected) return "Not connected"
        return when (box.projectionMode) {
            Ocbm.PM_WIRED_CP -> "Wired CarPlay running"
            Ocbm.PM_WIRELESS_CP -> "Wireless CarPlay running"
            else -> "Waiting for a phone"
        }
    }

    /** "Not paired with the adapter · app history only", "Last seen ..." or "Not connected". */
    fun deviceStatus(device: CarlinkManager.DeviceInfo): String =
        when {
            !device.bonded -> "Not paired with the adapter · app history only"
            else -> device.lastConnected?.let { "Last seen $it" } ?: "Not connected"
        }

    private fun isCarPlay(type: PhoneType): Boolean = type == PhoneType.CARPLAY || type == PhoneType.CARPLAY_WIRELESS
}

/** The scene as words, for the Loading screen choice and its summary ("Chevrolet", not "CHEVROLET"). */
val BackgroundLogoConfig.brandName: String
    get() =
        when (this) {
            BackgroundLogoConfig.GMC -> "GMC"
            BackgroundLogoConfig.CHEVROLET -> "Chevrolet"
            else -> label
        }
