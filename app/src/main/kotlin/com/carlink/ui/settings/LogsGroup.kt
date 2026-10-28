package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Usb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.carlink.CarlinkManager
import com.carlink.logging.VerboseLogging
import com.carlink.logging.logWarn
import com.carlink.ui.BridgeSetupNotification
import com.carlink.util.FilesLauncher
import kotlinx.coroutines.launch

/**
 * Troubleshoot → Logs: detailed logging, the always-on drive log, Save Logs, and Copy logs to USB.
 *
 * Save Logs pulls the box's RAM-only logs over the live link (they are gone at power-off), so it
 * needs a connected adapter. Detailed logging lifts the release build's WARN-only filter. Record
 * drive logs writes both as the drive goes ([com.carlink.logging.DriveLog]).
 */
@Composable
internal fun LogsGroup(
    carlinkManager: CarlinkManager,
    live: SettingsLiveState,
) {
    val context = LocalContext.current
    SettingsGroup {
        SwitchRow(
            title = "Detailed logging",
            subtitle = "More detail in saved logs",
            checked = live.verboseLogs,
            icon = Icons.Default.Description,
            onCheckedChange = { on ->
                live.verboseLogs = on
                logWarn("[UI_ACTION] Verbose Logs -> ${if (on) "on" else "off"}", tag = "UI")
                VerboseLogging.setEnabled(context, on)
            },
        )
        GroupDivider()
        SwitchRow(
            title = "Record drive logs",
            subtitle =
                if (live.driveLog) {
                    "Recording to ${live.driveLogFile ?: "Downloads/Carlink"}"
                } else {
                    "Writes app and adapter logs to Downloads/Carlink as you drive, kept 7 days"
                },
            checked = live.driveLog,
            icon = Icons.Default.FiberManualRecord,
            onCheckedChange = { on ->
                live.driveLog = on
                carlinkManager.setDriveLogging(on)
            },
        )
        GroupDivider()
        SaveLogsRow(carlinkManager, live)
        GroupDivider()
        CopyToUsbRow()
    }
}

/**
 * Opens the head unit's Files app on Downloads/Carlink, where Save logs and drive logs land, so
 * the driver can select them and Copy to a USB drive without adb. Files has no way back to
 * Carlink, so the same return notification as the bridge install is posted.
 */
@Composable
private fun CopyToUsbRow() {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    SettingRow(
        title = "Copy logs to USB",
        subtitle =
            if (failed) {
                "This head unit's Files app couldn't be opened"
            } else {
                "Opens Files at Downloads/Carlink. Select the logs, then Copy to your USB drive. Parked only."
            },
        icon = Icons.Default.Usb,
    ) {
        RowAction(
            label = "Open Files",
            onClick = {
                logWarn("[UI_ACTION] Copy logs to USB: open Files", tag = "UI")
                val opened = FilesLauncher.openFolder(context, subdir = "Carlink", logTag = "[LOGS]")
                if (opened) BridgeSetupNotification.showCopyingLogs(context)
                failed = !opened
            },
        )
    }
}

/** Save logs: pulls the box's RAM-only logs over the live link, so it needs a connected adapter. */
@Composable
private fun SaveLogsRow(
    carlinkManager: CarlinkManager,
    live: SettingsLiveState,
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    SettingRow(
        title = "Save logs",
        subtitle = result ?: "App and adapter logs for a bug report",
        icon = Icons.Default.Save,
    ) {
        RowAction(
            label = if (saving) "Saving…" else "Save",
            enabled = live.box.adapterConnected && !saving,
            onClick = {
                logWarn("[UI_ACTION] Save Logs button clicked", tag = "UI")
                saving = true
                result = null
                scope.launch {
                    result =
                        carlinkManager.saveLogs().fold(
                            onSuccess = { "Saved to $it" },
                            onFailure = { "Could not save logs: ${it.message}" },
                        )
                    saving = false
                }
            },
        )
    }
}
