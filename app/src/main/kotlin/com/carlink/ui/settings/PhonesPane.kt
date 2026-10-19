package com.carlink.ui.settings

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.logging.logWarn
import com.carlink.ui.components.DialogSystemBars

/**
 * Phones: the connected phone first, then the known list (the USB port and the adapter's bonded
 * phones merged with the app's history), then how to add one. Forget/Remove behave as before:
 * a bonded phone is cleared from the adapter too; a history-only record sends nothing.
 *
 * Disconnect stays visible but disabled until the box advertises a phone-disconnect verb
 * ([CarlinkManager.phoneDisconnectSupported]); a visible reason beats a missing button.
 */
@Composable
internal fun PhonesPane(
    carlinkManager: CarlinkManager,
    live: SettingsLiveState,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var deviceToRemove by remember { mutableStateOf<CarlinkManager.DeviceInfo?>(null) }
    val connected = live.connectedPhone

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column {
            PaneHeader(title = "Phones")
            AdapterNameLine(carlinkManager.adapterName)
        }
        if (connected != null) {
            ConnectedPhoneCard(
                phone = connected,
                disconnectSupported = carlinkManager.phoneDisconnectSupported,
                onDisconnect = {
                    logWarn("[UI_ACTION] Disconnect Phone clicked", tag = "UI")
                    carlinkManager.disconnectPhone()
                },
                onForget = connected.device?.let { device -> { deviceToRemove = device } },
            )
        } else {
            NoPhoneCard()
        }
        KnownPhones(
            usbInUse = connected != null && !connected.wireless,
            devices = live.devices.filterNot { it.btMac == connected?.device?.btMac },
            onRemove = { deviceToRemove = it },
        )
        AddPhoneHint(carlinkManager.adapterName)

        deviceToRemove?.let { device ->
            RemoveDeviceDialog(
                device = device,
                onConfirm = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    logWarn("[UI_ACTION] Forget device ${device.btMac} (bonded=${device.bonded})", tag = "UI")
                    carlinkManager.forgetDevice(device.btMac)
                    deviceToRemove = null
                },
                onDismiss = { deviceToRemove = null },
            )
        }
    }
}

@Composable
private fun AdapterNameLine(adapterName: String) {
    val colors = MaterialTheme.colorScheme
    Text(
        text =
            buildAnnotatedString {
                append("Pair with the adapter named ")
                withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium)) { append(adapterName) }
            },
        style = SettingsStyle.RowSubtitle,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** The phone the box reports right now, with its two actions. */
@Composable
private fun ConnectedPhoneCard(
    phone: ConnectedPhone,
    disconnectSupported: Boolean,
    onDisconnect: () -> Unit,
    onForget: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = SettingsStyle.GroupShape,
        color = SettingsStyle.ConnectedFill,
        border = BorderStroke(2.dp, SettingsStyle.ConnectedBorder),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 168.dp).padding(horizontal = 32.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            PhoneTile(highlighted = true)
            Column(modifier = Modifier.weight(1f)) {
                Text("CONNECTED NOW", style = SettingsStyle.SectionLabel, color = colors.primary)
                Text(phone.name, style = SettingsStyle.PaneTitle, modifier = Modifier.padding(top = 4.dp))
                Text(phone.kind, style = SettingsStyle.RowSubtitle, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RowAction(label = "Disconnect", onClick = onDisconnect, enabled = disconnectSupported)
                if (!disconnectSupported) {
                    Text("Needs adapter firmware support", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            if (onForget != null) RowAction(label = "Forget", onClick = onForget, weight = ActionWeight.DANGER_OUTLINE)
        }
    }
}

@Composable
private fun NoPhoneCard() {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = SettingsStyle.GroupShape,
        color = colors.surface.copy(alpha = 0f),
        border = BorderStroke(2.dp, colors.outlineVariant),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 168.dp).padding(horizontal = 32.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            PhoneTile(highlighted = false)
            Column(modifier = Modifier.weight(1f)) {
                Text("No phone connected", style = SettingsStyle.PaneTitle)
                Text(
                    "The adapter is ready. A known phone joins on its own when it's nearby.",
                    style = SettingsStyle.RowSubtitle,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PhoneTile(highlighted: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(96.dp),
        shape = RoundedCornerShape(26.dp),
        color = if (highlighted) colors.primaryContainer else colors.surfaceContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.PhoneIphone,
                contentDescription = null,
                tint = if (highlighted) colors.onPrimaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

/** USB port first, then every known wireless phone not already shown as connected. */
@Composable
private fun KnownPhones(
    usbInUse: Boolean,
    devices: List<CarlinkManager.DeviceInfo>,
    onRemove: (CarlinkManager.DeviceInfo) -> Unit,
) {
    SettingsGroup {
        SettingRow(
            title = "USB port",
            subtitle = if (usbInUse) "In use by the connected phone" else "Nothing plugged in",
            icon = Icons.Default.Usb,
        )
        if (devices.isEmpty()) {
            GroupDivider()
            SettingRow(title = "No other phones yet", subtitle = "Phones you pair with the adapter appear here")
        }
        devices.forEach { device ->
            key(device.btMac) {
                GroupDivider()
                val caution = !device.bonded
                SettingRow(
                    title = device.name,
                    subtitle = SettingsModel.deviceStatus(device),
                    icon = if (caution) Icons.Default.Warning else Icons.Default.PhoneIphone,
                    tint = if (caution) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    RowAction(
                        label = if (device.bonded) "Forget" else "Remove",
                        onClick = { onRemove(device) },
                        weight = if (device.bonded) ActionWeight.DANGER_OUTLINE else ActionWeight.NORMAL,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddPhoneHint(adapterName: String) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = SettingsStyle.GroupShape,
        color = colors.surface.copy(alpha = 0f),
        border = BorderStroke(2.dp, colors.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = colors.primary, modifier = Modifier.size(32.dp))
            Text(
                text =
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append("Add a phone: ") }
                        append("on the iPhone, open Settings › General › CarPlay and choose ")
                        withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium)) { append(adapterName) }
                        append(". Or plug it into the adapter's USB port.")
                    },
                style = SettingsStyle.Body,
            )
        }
    }
}

/**
 * Forget confirmation. A bonded record is cleared from the app's history AND the adapter (link key
 * + AirPlay peer store), so the phone pairs as new next time; a history-only record sends nothing.
 */
@Composable
private fun RemoveDeviceDialog(
    device: CarlinkManager.DeviceInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            DialogSystemBars()
            Text(if (device.bonded) "Forget ${device.name}?" else "Remove ${device.name}?")
        },
        text = {
            Text(
                if (device.bonded) {
                    "The adapter forgets this phone too. Next time it connects, it pairs as a new phone."
                } else {
                    "The adapter is not paired with this phone, so only this app's history entry is removed."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = SettingsStyle.ErrorText)) {
                Text(if (device.bonded) "Forget" else "Remove")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
