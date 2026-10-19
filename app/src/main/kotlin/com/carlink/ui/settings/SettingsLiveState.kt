package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhoneIphone
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
import com.carlink.logging.VerboseLogging
import kotlinx.coroutines.delay

/** The merged settings screen's categories, in list order. */
enum class SettingsCategory(
    val title: String,
    val icon: ImageVector,
) {
    PHONES("Phones", Icons.Default.PhoneIphone),
    DISPLAY("Display", Icons.Default.DesktopWindows),
    CALLS("Calls", Icons.Default.Mic),
    CLUSTER("Cluster", Icons.Default.Navigation),
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

    val connectedPhone: ConnectedPhone? get() = SettingsModel.connectedPhone(phone, devices)
}

/** Poll interval for the manager's phone and box fields while Settings is open. */
private const val POLL_MS = 1000L

/**
 * Polls the manager once a second while Settings is open. The manager's single callback slot is
 * MainScreen's, and its listeners do not cover phone-type or wifi changes, so polling is the one
 * path that sees every field (the old Phones tab did the same).
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
            live.phone = PhoneSnapshot(carlinkManager.currentPhoneType, carlinkManager.currentWifi ?: -1, carlinkManager.connectedBtMac)
            live.devices = carlinkManager.pairedDevices
            live.box =
                BoxSnapshot(
                    adapterConnected = connectionState != CarlinkManager.State.DISCONNECTED,
                    projectionMode = carlinkManager.projectionMode,
                    health = carlinkManager.boxHealth,
                    healthKnown = carlinkManager.boxHealthKnown,
                    raw = carlinkManager.boxStatusText(),
                )
            delay(POLL_MS)
        }
    }
    return live
}
