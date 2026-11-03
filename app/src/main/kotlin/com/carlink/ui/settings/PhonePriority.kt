package com.carlink.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.CarlinkManager
import com.carlink.logging.logInfo
import com.carlink.ocbm.ConnectAttempt
import com.carlink.ui.components.WorkingDots
import com.carlink.ui.theme.LocalCarlinkColors

/** Whether the phone list shows ranking controls, and which phone is live. */
@Immutable
internal class PhoneRanking(
    /** The box takes phone priority (CAP_PHONE_POLICY). Off: the list is the old Forget-only list. */
    val enabled: Boolean,
    val connectedMac: String?,
    /** Any phone is projecting (USB or wireless): a plain connect won't interrupt it. */
    val phoneConnected: Boolean,
    /**
     * The projecting phone is on Wi-Fi and the box switches phones (CAP_PHONE_SWITCH): Connect ends its session for
     * the phone tapped (owner, 11-01). A phone on USB can't be ended from here.
     */
    val switchable: Boolean = false,
    /** The Connect press being followed, or its outcome while it stands (owner, 11-01: show the press). */
    val attempt: ConnectAttempt? = null,
) {
    /**
     * Whether [mac]'s row shows as the phone connected now: the one the adapter names, unless a Connect press for another
     * phone is working or has connected it (11-03 bench: through a switch the old phone's row still read "Connected" while
     * the new phone's said it had connected).
     */
    fun isLive(mac: String): Boolean {
        val other = attempt?.takeUnless { it.mac.equals(mac, ignoreCase = true) }
        return mac == connectedMac && (other == null || other.outcome == ConnectAttempt.Outcome.NOT_REACHED)
    }

    /**
     * Whether row [index]'s rank badge carries the accent: the phone connected now ([live]) or, with none connected, the
     * one called first (owner, 11-03: the second phone was connected and its badge stayed plain while the first's was lit).
     */
    fun badgeLit(
        index: Int,
        live: Boolean,
    ): Boolean = live || (index == 0 && !phoneConnected)
}

/** Whether a row's Connect works: a bonded phone that isn't the live one, with no phone projecting or one [PhoneRanking.switchable]. */
internal fun connectEnabled(
    bonded: Boolean,
    live: Boolean,
    ranking: PhoneRanking,
): Boolean = bonded && !live && (!ranking.phoneConnected || ranking.switchable) && ranking.attempt?.working != true

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
    val attempt by carlinkManager.connectAttempt.collectAsState()
    KnownPhones(
        usbInUse = connected != null && !connected.wireless,
        devices = live.devices,
        ranking =
            PhoneRanking(
                live.policySupported,
                connected?.device?.btMac,
                phoneConnected = connected != null,
                switchable = live.switchSupported && connected?.wireless == true,
                attempt = attempt,
            ),
        actions =
            PhoneListActions(
                onMove = { mac, delta -> carlinkManager.movePhone(mac, delta) },
                onConnect = { carlinkManager.connectPhone(it.btMac, switching = connected != null) },
                onRemove = onRemove,
                onSettings = settingsFor,
            ),
    )
}

/**
 * Every known phone in the order the box will call them (design P2), then the USB port (owner, 11-03: at the
 * bottom). With phone priority each phone carries its rank, up/down buttons and Connect; the list order on
 * screen is the order sent to the box, so moving a row is the whole interaction.
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
        if (shown.isEmpty()) {
            SettingRow(title = "No other phones yet", subtitle = "Phones you pair with the adapter appear here")
        }
        shown.forEachIndexed { index, device ->
            key(device.btMac) {
                if (index > 0) GroupDivider()
                PhoneRow(device, index, shown.size, ranking, actions)
            }
        }
        GroupDivider()
        SettingRow(
            title = "USB port",
            subtitle = if (usbInUse) "In use by the connected phone" else "Nothing plugged in",
            icon = Icons.Default.Usb,
        )
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
    val live = ranking.isLive(device.btMac)
    val mine = ranking.attempt?.takeIf { it.mac.equals(device.btMac, ignoreCase = true) }
    Row(modifier = Modifier.padding(start = if (ranking.enabled) 28.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ranking.enabled) RankBadge(index + 1, accent = ranking.badgeLit(index, live))
        SettingRow(
            title = device.name,
            subtitle = phoneSubtitle(device, live, first = ranking.enabled && index == 0, mine),
            icon = phoneIcon(device, ranking.enabled),
            tint = if (device.bonded) colors.onSurfaceVariant else colors.tertiary,
            modifier = Modifier.weight(1f),
        ) {
            if (ranking.enabled) {
                RankControls(device, index, count, live, connectEnabled(device.bonded, live, ranking), mine?.working == true, actions)
            }
            actions.onSettings(device)?.let { RowAction(label = "Settings", onClick = it) }
            RowAction(
                label = if (device.bonded) "Forget" else "Remove",
                onClick = { actions.onRemove(device) },
                weight = if (device.bonded) ActionWeight.DANGER_OUTLINE else ActionWeight.NORMAL,
            )
        }
    }
}

/**
 * "Connected now" / "Last seen …", plus "called first" on the top bonded phone. A Connect press on this phone
 * that ended ([attempt]) says how, for a while: "Connected", or "Couldn't reach <phone>".
 */
private fun phoneSubtitle(
    device: CarlinkManager.DeviceInfo,
    live: Boolean,
    first: Boolean,
    attempt: ConnectAttempt?,
): String {
    val status =
        when (attempt?.outcome) {
            ConnectAttempt.Outcome.CONNECTED -> "Connected"
            ConnectAttempt.Outcome.NOT_REACHED -> "Couldn't reach ${device.name}"
            null -> if (live) "Connected now" else SettingsModel.deviceStatus(device)
        }
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
    canConnect: Boolean,
    connecting: Boolean,
    actions: PhoneListActions,
) {
    // One Row, spaced and aligned like SettingRow's trailing slot, so this emits a single node
    // (ComposeMultipleContentEmitters).
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        MoveButton(up = true, enabled = index > 0, name = device.name) { actions.onMove(device.btMac, -1) }
        MoveButton(up = false, enabled = index < count - 1, name = device.name) { actions.onMove(device.btMac, 1) }
        ConnectButton(
            label = if (live) "Connected" else "Connect",
            enabled = canConnect || connecting,
            connecting = connecting,
            onClick = {
                logInfo("[UI_ACTION] Phones: Connect", tag = "UI") // no name: the logs hide phone names
                actions.onConnect(device)
            },
        )
    }
}

/**
 * Connect, styled as [RowAction]: while its press works the label becomes [WorkingDots], pulsing and waving,
 * and further taps do nothing (owner, 11-01: with nothing on screen after a tap, the driver pressed again,
 * then tried Forget).
 */
@Composable
private fun ConnectButton(
    label: String,
    enabled: Boolean,
    connecting: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val extra = LocalCarlinkColors.current
    Button(
        onClick = { if (!connecting) onClick() },
        enabled = enabled,
        modifier = Modifier.heightIn(min = SettingsStyle.ActionHeight),
        contentPadding = PaddingValues(horizontal = 28.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer,
                disabledContainerColor = extra.disabledFill,
                disabledContentColor = extra.disabledText,
            ),
    ) {
        if (connecting) WorkingDots(description = "Connecting", style = SettingsStyle.Action) else Text(label, style = SettingsStyle.Action)
    }
}

/** The rank, in the accent on the phone connected now or, with none connected, the one called first ([PhoneRanking.badgeLit]). */
@Composable
private fun RankBadge(
    rank: Int,
    accent: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(56.dp),
        shape = CircleShape,
        color = if (accent) colors.primary else colors.surfaceContainerHigh,
        contentColor = if (accent) colors.onPrimary else colors.onSurface,
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
