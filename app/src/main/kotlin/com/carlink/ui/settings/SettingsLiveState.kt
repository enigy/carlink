package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Usb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.carlink.CarlinkManager
import com.carlink.cluster.ClusterNavigation
import com.carlink.logging.DriveLog
import com.carlink.logging.VerboseLogging
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The merged settings screen's categories, in list order. */
enum class SettingsCategory(
    val title: String,
    val icon: ImageVector,
) {
    PHONES("Phones", Icons.Default.PhoneIphone),
    DISPLAY("Display", Icons.Default.DesktopWindows),
    CLUSTER("Cluster & HUD", Icons.Default.Navigation),
    CALLS("Calls", Icons.Default.Mic),

    /** Listed only while other apps' access is on (bridge API 6; Adapter and about › USB bridge). */
    BRIDGE("Bridge Configuration", Icons.Default.Usb),
    TROUBLESHOOT("Troubleshoot", Icons.Default.Build),
    ADAPTER("Adapter and about", Icons.Default.Memory),
}

/**
 * What the category list and the panes read live while Settings is open. One holder so a change in
 * a pane (the cluster switch, a forgotten phone) shows in the list's summary at once.
 */
@Stable
internal class SettingsLiveState(
    clusterOn: Boolean,
    verboseLogs: Boolean,
) {
    var phone by mutableStateOf(PhoneSnapshot())
    var devices by mutableStateOf(emptyList<CarlinkManager.DeviceInfo>())
    var box by mutableStateOf(BoxSnapshot())
    var clusterOn by mutableStateOf(clusterOn)
    var verboseLogs by mutableStateOf(verboseLogs)

    /** Drive logging: on, the file it is writing, and why it writes nothing when it doesn't (from [com.carlink.logging.DriveLog]). */
    var driveLog by mutableStateOf(false)
    var driveLogFile by mutableStateOf<String?>(null)
    var driveLogTrouble by mutableStateOf<String?>(null)

    /** The box takes phone priority (CAP_PHONE_POLICY), and the auto-connect flag the app holds. */
    var policySupported by mutableStateOf(false)

    /** The name the driver picks on the iPhone: an OCBM adapter's own, from its latest MGMT_INFO ([CarlinkManager.adapterName]). */
    var adapterName by mutableStateOf("")

    /** The box switches phones (CAP_PHONE_SWITCH): Connect works while another phone projects over Wi-Fi. */
    var switchSupported by mutableStateOf(false)

    /** The box gates pairing behind a window (CAP_PAIR_WINDOW), and whether it says the window is open. */
    var pairWindowSupported by mutableStateOf(false)
    var pairWindowOpen by mutableStateOf(false)

    /** The adapter holds no paired phone, so it stays pairable without a window ([CarlinkManager.noPhoneBonded]). */
    var noPhoneBonded by mutableStateOf(false)

    /** Forget reaches the adapter: it is connected and its pairings are known ([CarlinkManager.canForget]). */
    var canForget by mutableStateOf(false)

    /** AAOS says the truck is moving: another iPhone's profile, copy and reset wait until parked. */
    var driving by mutableStateOf(false)

    val connectedPhone: ConnectedPhone? get() = SettingsModel.connectedPhone(phone, devices)
}

/** Poll interval for the manager's phone and box fields while Settings is open. */
private const val POLL_MS = 1000L

/**
 * Polls the manager once a second while Settings is open, and at once when the adapter says which phone is on it
 * ([CarlinkManager.phoneNews]; 11-03 bench: a second's lag put "Connected" on the wrong phone during a switch). The
 * manager's single callback slot is MainScreen's, and its listeners do not cover phone-type or wifi changes, so
 * polling is the one path that sees every field (the old Phones tab did the same).
 */
@Composable
internal fun rememberSettingsLiveState(
    carlinkManager: CarlinkManager,
    connectionState: CarlinkManager.State,
    active: Boolean = true,
): SettingsLiveState {
    val context = LocalContext.current
    val live =
        remember(carlinkManager) {
            SettingsLiveState(ClusterNavigation.isEnabled(context), VerboseLogging.isEnabled(context))
        }
    // The page stays composed while closed; it only asks the box and polls while open.
    LaunchedEffect(carlinkManager, active) { if (active) carlinkManager.refreshDeviceList() }
    LaunchedEffect(carlinkManager, connectionState, active) {
        while (active) {
            val news = carlinkManager.phoneNews.value
            live.phone =
                PhoneSnapshot(
                    carlinkManager.currentPhoneType,
                    carlinkManager.phoneOnWifi ?: -1,
                    carlinkManager.connectedBtMac,
                    present = carlinkManager.phoneOnAdapter,
                )
            live.devices = carlinkManager.pairedDevices
            live.policySupported = carlinkManager.phonePolicySupported
            live.adapterName = carlinkManager.adapterName
            live.switchSupported = carlinkManager.phoneSwitchSupported
            live.pairWindowSupported = carlinkManager.pairWindowSupported
            live.pairWindowOpen = carlinkManager.pairWindowOpen
            live.noPhoneBonded = carlinkManager.noPhoneBonded
            live.canForget = carlinkManager.canForget
            live.driving = carlinkManager.isDriving
            val install = carlinkManager.oneFileInstall.install.value
            live.box =
                BoxSnapshot(
                    adapterConnected = connectionState != CarlinkManager.State.DISCONNECTED,
                    projectionMode = carlinkManager.projectionMode,
                    health = carlinkManager.boxHealth,
                    healthKnown = carlinkManager.boxHealthKnown,
                    raw = carlinkManager.boxStatusText(),
                    stockKept = install?.mode?.stockKept == true,
                )
            live.driveLog = DriveLog.recording
            live.driveLogFile = DriveLog.currentFile
            live.driveLogTrouble = DriveLog.trouble()
            // Directions are per driver: an iPhone connecting while Settings is open can change them.
            live.clusterOn = ClusterNavigation.isEnabled(context)
            withTimeoutOrNull(POLL_MS) { carlinkManager.phoneNews.first { it != news } }
        }
    }
    return live
}
