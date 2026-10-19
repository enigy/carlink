package com.carlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.logging.logWarn
import com.carlink.ui.components.DialogSystemBars
import com.carlink.ui.components.OnSettingsHidden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The confirmations this pane can have open. */
private enum class Fix { RESTART_WIRELESS, RESET_CONNECTION, REBOOT, DISCONNECT_ADAPTER }

/**
 * Troubleshoot: every recovery action, least disruptive first, each saying what it interrupts.
 * What each sends is unchanged from the old Control tab (OCBMANDROID.md mapping):
 *  - Reset video: decoder reset, no confirmation (the session stays up).
 *  - Restart wireless: `MGMT_RESTART_WIRELESS`; a wired phone is not affected.
 *  - Reset connection: the clean-slate reset ([CarlinkManager.cleanSlateReset]).
 *  - Reboot adapter: `MGMT_REBOOT`; ~50 s absence and a USB permission prompt after.
 *  - Disconnect adapter: CT_STOP + release USB, off the main thread.
 */
@Composable
internal fun TroubleshootPane(
    carlinkManager: CarlinkManager,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf<Fix?>(null) }
    OnSettingsHidden { confirm = null }
    var resetting by remember(carlinkManager) { mutableStateOf(carlinkManager.resetInFlight) }

    Column(modifier = modifier.widthIn(max = 1300.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PaneHeader(title = "Troubleshoot")
            Text(
                "Least disruptive first",
                style = SettingsStyle.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        FixLadder(
            connected = connected,
            resetting = resetting,
            onResetVideo = {
                logWarn("[UI_ACTION] Reset Decoder button clicked", tag = "UI")
                carlinkManager.resetVideoDecoder()
            },
            onPick = { confirm = it },
        )
        Text("Each fix asks you to confirm before it interrupts anything.", style = SettingsStyle.RowSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FixConfirmation(confirm, carlinkManager, onDone = { confirm = null }, onResetting = { resetting = it })
    }
}

@Composable
private fun FixLadder(
    connected: Boolean,
    resetting: Boolean,
    onResetVideo: () -> Unit,
    onPick: (Fix) -> Unit,
) {
    SettingsGroup {
        FixRow("Reset video", "Frozen or black screen. Stays connected.", Icons.Default.VideoSettings, "Reset", enabled = !resetting, onClick = onResetVideo)
        GroupDivider()
        FixRow("Restart wireless", "Wireless phone drops and rejoins. USB is not affected.", Icons.Default.Wifi, "Restart", enabled = connected) {
            onPick(Fix.RESTART_WIRELESS)
        }
        GroupDivider()
        FixRow("Reset connection", "Fresh session. About 10 seconds without CarPlay.", Icons.Default.Sync, "Reset", enabled = connected && !resetting) {
            onPick(Fix.RESET_CONNECTION)
        }
        GroupDivider()
        FixRow("Reboot adapter", "About 50 seconds offline. May ask for USB permission.", Icons.Default.PowerSettingsNew, "Reboot", weight = ActionWeight.CAUTION) {
            onPick(Fix.REBOOT)
        }
        GroupDivider()
        FixRow(
            "Disconnect adapter",
            "Stays off until you reset the connection or replug.",
            Icons.Default.PowerOff,
            "Disconnect",
            enabled = connected,
            weight = ActionWeight.DANGER,
        ) { onPick(Fix.DISCONNECT_ADAPTER) }
    }
}

/** The confirmation for whichever fix is pending, and the action it guards. */
@Composable
private fun FixConfirmation(
    fix: Fix?,
    carlinkManager: CarlinkManager,
    onDone: () -> Unit,
    onResetting: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    when (fix) {
        Fix.RESTART_WIRELESS ->
            ConfirmDialog(RESTART_WIRELESS_CONFIRM, onDismiss = onDone) {
                logWarn("[UI_ACTION] Restart Wireless confirmed", tag = "UI")
                onDone()
                carlinkManager.restartWireless()
            }
        Fix.RESET_CONNECTION ->
            ResetConnectionDialog(onDismiss = onDone, onConfirm = {
                logWarn("[UI_ACTION] Reset Connection confirmed", tag = "UI")
                onDone()
                onResetting(true)
                scope.launch {
                    try {
                        carlinkManager.cleanSlateReset()
                    } finally {
                        onResetting(false)
                    }
                }
            })
        Fix.REBOOT ->
            ConfirmDialog(REBOOT_CONFIRM, onDismiss = onDone) {
                logWarn("[UI_ACTION] Reboot Adapter confirmed", tag = "UI")
                onDone()
                scope.launch(Dispatchers.IO) { carlinkManager.rebootAdapter() }
            }
        Fix.DISCONNECT_ADAPTER ->
            ConfirmDialog(DISCONNECT_ADAPTER_CONFIRM, onDismiss = onDone) {
                logWarn("[UI_ACTION] Disconnect Adapter confirmed", tag = "UI")
                onDone()
                scope.launch { carlinkManager.disconnect() }
            }
        null -> Unit
    }
}

@Suppress("LongParameterList") // one row of the ladder: its words, its icon, its button
@Composable
private fun FixRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    action: String,
    enabled: Boolean = true,
    weight: ActionWeight = ActionWeight.NORMAL,
    onClick: () -> Unit,
) {
    val tint =
        when (weight) {
            ActionWeight.CAUTION -> MaterialTheme.colorScheme.tertiary
            ActionWeight.DANGER, ActionWeight.DANGER_OUTLINE -> SettingsStyle.ErrorText
            ActionWeight.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    SettingRow(title = title, subtitle = subtitle, icon = icon, tint = tint) {
        RowAction(label = action, onClick = onClick, enabled = enabled, weight = weight, modifier = Modifier.widthIn(min = 180.dp))
    }
}

/** Text of one confirmation dialog. */
private class ConfirmSpec(
    val icon: ImageVector,
    val title: String,
    val text: String,
    val confirmLabel: String,
)

private val RESTART_WIRELESS_CONFIRM =
    ConfirmSpec(
        icon = Icons.Default.Wifi,
        title = "Restart wireless?",
        text =
            "The adapter restarts its Bluetooth and Wi-Fi and advertises again. " +
                "A wirelessly connected phone will drop and reconnect; a phone on the USB cable is not affected.",
        confirmLabel = "Restart wireless",
    )

private val REBOOT_CONFIRM =
    ConfirmSpec(
        icon = Icons.Default.RestartAlt,
        title = "Reboot adapter?",
        text =
            "The adapter will reboot and any live projection session will drop. " +
                "It is gone for about 50 seconds while it restarts and re-enumerates on USB; " +
                "Android may then ask for USB permission again before the app reconnects.",
        confirmLabel = "Reboot",
    )

private val DISCONNECT_ADAPTER_CONFIRM =
    ConfirmSpec(
        icon = Icons.Default.PowerOff,
        title = "Disconnect adapter?",
        text =
            "Ends the session with the adapter and releases it. Any live projection drops; " +
                "the app stays open and reconnects when you use Reset connection or replug the adapter.",
        confirmLabel = "Disconnect",
    )

private val RESET_CONNECTION_CONFIRM =
    ConfirmSpec(
        icon = Icons.Default.Usb,
        title = "Reset connection?",
        text =
            "Ends the current phone session and the link to the adapter, waits for the adapter to " +
                "finish shutting the session down, then reconnects and starts a new session. " +
                "The phone reconnects on its own; expect about 10 seconds without projection.",
        confirmLabel = "Reset",
    )

/** One confirmation shape for every disruptive adapter action. */
@Composable
private fun ConfirmDialog(
    spec: ConfirmSpec,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = spec.icon, contentDescription = null, tint = colorScheme.tertiary) },
        title = {
            DialogSystemBars() // keep the head unit's bars hidden while this window has focus
            Text(spec.title)
        },
        text = { Text(spec.text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(spec.confirmLabel, color = SettingsStyle.ErrorText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Confirmation for the clean-slate reset — shared with the loading overlay's Reset Device
 * (`MainScreen`), which runs the same [CarlinkManager.cleanSlateReset].
 */
@Composable
internal fun ResetConnectionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(RESET_CONNECTION_CONFIRM, onDismiss = onDismiss, onConfirm = onConfirm)
}
