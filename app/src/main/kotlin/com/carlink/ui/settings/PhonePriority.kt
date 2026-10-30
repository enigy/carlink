package com.carlink.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.CarlinkManager

/** Whether the phone list shows ranking controls, and which phone is live. */
@Immutable
internal class PhoneRanking(
    /** The box takes phone priority (CAP_PHONE_POLICY). Off: the list is the old Forget-only list. */
    val enabled: Boolean,
    val connectedMac: String?,
    /** Any phone is projecting (USB or wireless): Connect is pointless then, the box won't interrupt it. */
    val phoneConnected: Boolean,
)

/** What a row's buttons do. */
@Immutable
internal class PhoneListActions(
    val onMove: (mac: String, delta: Int) -> Unit,
    val onConnect: (CarlinkManager.DeviceInfo) -> Unit,
    val onRemove: (CarlinkManager.DeviceInfo) -> Unit,
    /** The phone's own Cluster and Driving settings, or null when it has none to show now. */
    val onSettings: (CarlinkManager.DeviceInfo) -> (() -> Unit)?,
)

/** The ranked phone list. The adapter always calls these phones, in this order, when the vehicle starts. */
@Composable
internal fun PhoneList(
    carlinkManager: CarlinkManager,
    live: SettingsLiveState,
    connected: ConnectedPhone?,
    onRemove: (CarlinkManager.DeviceInfo) -> Unit,
    settingsFor: (CarlinkManager.DeviceInfo) -> (() -> Unit)?,
) {
    KnownPhones(
        usbInUse = connected != null && !connected.wireless,
        devices = live.devices,
        ranking = PhoneRanking(live.policySupported, connected?.device?.btMac, phoneConnected = connected != null),
        actions =
            PhoneListActions(
                onMove = { mac, delta -> carlinkManager.movePhone(mac, delta) },
                onConnect = { carlinkManager.connectPhone(it.btMac) },
                onRemove = onRemove,
                onSettings = settingsFor,
            ),
    )
}

/**
 * The USB port, then every known phone in the order the box will call them (design P2). With phone
 * priority each phone carries its rank, up/down buttons and Connect; the list order on screen is
 * the order sent to the box, so moving a row is the whole interaction.
 */
@Composable
internal fun KnownPhones(
    usbInUse: Boolean,
    devices: List<CarlinkManager.DeviceInfo>,
    ranking: PhoneRanking,
    actions: PhoneListActions,
) {
    val shown = if (ranking.enabled) devices else devices.filterNot { it.btMac == ranking.connectedMac }
    SettingsGroup {
        SettingRow(
            title = "USB port",
            subtitle = if (usbInUse) "In use by the connected phone" else "Nothing plugged in",
            icon = Icons.Default.Usb,
        )
        if (shown.isEmpty()) {
            GroupDivider()
            SettingRow(title = "No other phones yet", subtitle = "Phones you pair with the adapter appear here")
        }
        shown.forEachIndexed { index, device ->
            key(device.btMac) {
                GroupDivider()
                PhoneRow(device, index, shown.size, ranking, actions)
            }
        }
    }
}

@Composable
private fun PhoneRow(
    device: CarlinkManager.DeviceInfo,
    index: Int,
    count: Int,
    ranking: PhoneRanking,
    actions: PhoneListActions,
) {
    val colors = MaterialTheme.colorScheme
    val live = device.btMac == ranking.connectedMac
    Row(modifier = Modifier.padding(start = if (ranking.enabled) 28.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ranking.enabled) RankBadge(index + 1, first = index == 0)
        SettingRow(
            title = device.name,
            subtitle = phoneSubtitle(device, live, first = ranking.enabled && index == 0),
            icon = phoneIcon(device, ranking.enabled),
            tint = if (device.bonded) colors.onSurfaceVariant else colors.tertiary,
            modifier = Modifier.weight(1f),
        ) {
            if (ranking.enabled) RankControls(device, index, count, live, ranking.phoneConnected, actions)
            actions.onSettings(device)?.let { RowAction(label = "Settings", onClick = it) }
            RowAction(
                label = if (device.bonded) "Forget" else "Remove",
                onClick = { actions.onRemove(device) },
                weight = if (device.bonded) ActionWeight.DANGER_OUTLINE else ActionWeight.NORMAL,
            )
        }
    }
}

/** "Connected now" / "Last seen …", plus "called first" on the top bonded phone. */
private fun phoneSubtitle(
    device: CarlinkManager.DeviceInfo,
    live: Boolean,
    first: Boolean,
): String {
    val status = if (live) "Connected now" else SettingsModel.deviceStatus(device)
    return if (first && device.bonded) "$status · called first" else status
}

/** Ranked rows carry the rank badge instead of an icon; unranked rows keep the phone/warning icon. */
private fun phoneIcon(
    device: CarlinkManager.DeviceInfo,
    ranked: Boolean,
) = when {
    ranked -> null
    device.bonded -> Icons.Default.PhoneIphone
    else -> Icons.Default.Warning
}

@Suppress("LongParameterList") // one row's ranking controls: its phone, place, state and callbacks
@Composable
private fun RankControls(
    device: CarlinkManager.DeviceInfo,
    index: Int,
    count: Int,
    live: Boolean,
    phoneConnected: Boolean,
    actions: PhoneListActions,
) {
    // One Row, spaced and aligned like SettingRow's trailing slot, so this emits a single node
    // (ComposeMultipleContentEmitters).
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        MoveButton(up = true, enabled = index > 0, name = device.name) { actions.onMove(device.btMac, -1) }
        MoveButton(up = false, enabled = index < count - 1, name = device.name) { actions.onMove(device.btMac, 1) }
        RowAction(
            label = if (live) "Connected" else "Connect",
            enabled = device.bonded && !phoneConnected,
            onClick = { actions.onConnect(device) },
        )
    }
}

/** "1" in the accent for the phone called first, plain numbers after it. */
@Composable
private fun RankBadge(
    rank: Int,
    first: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(56.dp),
        shape = CircleShape,
        color = if (first) colors.primary else colors.surfaceContainerHigh,
        contentColor = if (first) colors.onPrimary else colors.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(rank.toString(), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MoveButton(
    up: Boolean,
    enabled: Boolean,
    name: String,
    onClick: () -> Unit,
) {
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(72.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Icon(
            imageVector = if (up) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
            contentDescription = if (up) "Move $name up" else "Move $name down",
            modifier = Modifier.size(30.dp),
        )
    }
}
