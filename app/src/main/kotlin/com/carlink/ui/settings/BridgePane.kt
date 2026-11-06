package com.carlink.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.ui.components.CarlinkDialog
import com.carlink.ui.components.DialogWeight
import com.carlink.usb.BridgeAccess
import com.carlink.usb.BridgeAccessHub
import com.carlink.usb.BridgeAccessText
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Adapter and about › USB bridge (bridge 1.0.8, API 6): the bridge's version, and the owner's opt-in that lets apps
 * they approve use the adapter. Turning it on is for parked, behind the risk dialog; turning it off works while
 * driving and needs no dialog.
 */
@Composable
internal fun UsbBridgeGroup(
    live: SettingsLiveState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val access by BridgeAccessHub.state.collectAsState()
    var askRisk by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    val switch = BridgeAccessText.switchState(access, live.driving)
    SettingsGroup(modifier = modifier) {
        SettingRow(title = "USB bridge", icon = Icons.Default.Usb) {
            Text(access.bridgeVersion ?: "- - -", style = SettingsStyle.RowTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GroupDivider()
        SwitchRow(
            title = BridgeAccessText.SWITCH_TITLE,
            subtitle = switch.subtitle,
            checked = switch.checked,
            enabled = switch.enabled,
            onCheckedChange = { on ->
                if (on) {
                    askRisk = true
                } else {
                    scope.launch { refusal = BridgeAccessHub.setAllowed(context, on = false) }
                }
            },
        )
        RefusalLine(refusal)
    }
    if (askRisk) {
        CarlinkDialog(
            title = BridgeAccessText.RISK_TITLE,
            text = BridgeAccessText.RISK_TEXT,
            confirmLabel = "Accept",
            weight = DialogWeight.CAUTION,
            icon = Icons.Default.Usb,
            // Parked only: the switch is disabled while driving, and a drive starting under the dialog greys Accept.
            confirmEnabled = !live.driving,
            onConfirm = {
                askRisk = false
                scope.launch { refusal = BridgeAccessHub.setAllowed(context, on = true) }
            },
            onDismiss = { askRisk = false },
        )
    }
}

/**
 * Bridge Configuration, listed while other apps' access is on: who has the adapter (with Take back), the approved
 * apps (Hand over, Remove), the apps asking for access (Approve, Dismiss), and turning access off. Hand over and
 * Approve are for parked; Take back, Remove, Dismiss and Turn off work while driving.
 */
@Composable
internal fun BridgePane(
    carlinkManager: CarlinkManager,
    live: SettingsLiveState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val access by BridgeAccessHub.state.collectAsState()
    // The last refusal, and the row it belongs to (a package, or "" for the pane).
    var refusal by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirm by remember { mutableStateOf<BridgeConfirm?>(null) }
    val clock: (Long) -> String = { DateFormat.getTimeFormat(context).format(Date(it)) }
    val run: (String, suspend () -> String?) -> Unit = { row, action ->
        scope.launch { refusal = action()?.let { row to it } }
    }
    val refusalFor: (String) -> String? = { row -> refusal?.takeIf { it.first == row }?.second }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        PaneHeader(title = "Bridge Configuration", subtitle = "Apps you approve can use the adapter while you hand it over.")
        SettingsGroup {
            SettingRow(title = "Who has the adapter", subtitle = BridgeAccessText.holderLine(access, clock), icon = Icons.Default.Usb) {
                if (access.handedTo != null) RowAction("Take back", onClick = { run(HOLDER_ROW) { carlinkManager.takeBackAdapter() } })
            }
            RefusalLine(refusalFor(HOLDER_ROW))
        }
        ApprovedApps(access, RowContext(live.driving, clock, refusalFor), onConfirm = { confirm = it })
        AskingApps(
            access,
            RowContext(live.driving, clock, refusalFor),
            onApprove = { confirm = BridgeConfirm.Approve(it) },
            onDismiss = { request -> run(request.packageName) { BridgeAccessHub.dismiss(context, request.packageName) } },
        )
        SettingsGroup {
            SettingRow(title = BridgeAccessText.TURN_OFF, subtitle = "Removes every approved app and request.", icon = Icons.Default.Block, tint = SettingsStyle.ErrorText) {
                RowAction("Turn off", weight = ActionWeight.DANGER, onClick = { run(PANE_ROW) { BridgeAccessHub.setAllowed(context, on = false) } })
            }
            RefusalLine(refusalFor(PANE_ROW))
        }
    }

    confirm?.let { asked ->
        BridgeConfirmDialog(asked, live.driving, onDismiss = { confirm = null }) {
            confirm = null
            when (asked) {
                is BridgeConfirm.HandOver -> run(asked.app.packageName) { carlinkManager.handOverAdapter(asked.app.packageName) }
                is BridgeConfirm.Remove -> run(asked.app.packageName) { BridgeAccessHub.revoke(context, asked.app.packageName) }
                is BridgeConfirm.Approve -> run(asked.request.packageName) { BridgeAccessHub.approve(context, asked.request) }
            }
        }
    }
}

/** What every row of the pane shows against: driving (parked-only actions grey out), the clock, and its last refusal. */
private class RowContext(
    val driving: Boolean,
    val clock: (Long) -> String,
    val refusalFor: (String) -> String?,
)

@Composable
private fun ApprovedApps(
    access: BridgeAccess,
    rows: RowContext,
    onConfirm: (BridgeConfirm) -> Unit,
) {
    SectionLabel("Approved apps")
    SettingsGroup {
        if (access.approved.isEmpty()) SettingRow(title = BridgeAccessText.NO_APPROVED, icon = Icons.Default.Apps)
        access.approved.forEachIndexed { i, app ->
            if (i > 0) GroupDivider()
            ApprovedRow(
                app,
                handedToIt = access.handedTo?.packageName == app.packageName,
                rows,
                onHandOver = { onConfirm(BridgeConfirm.HandOver(app)) },
                onRemove = { onConfirm(BridgeConfirm.Remove(app)) },
            )
            RefusalLine(rows.refusalFor(app.packageName))
        }
    }
}

@Composable
private fun AskingApps(
    access: BridgeAccess,
    rows: RowContext,
    onApprove: (BridgeAccess.AccessRequest) -> Unit,
    onDismiss: (BridgeAccess.AccessRequest) -> Unit,
) {
    if (access.requests.isEmpty()) return
    SectionLabel("Asking for access")
    SettingsGroup {
        access.requests.forEachIndexed { i, request ->
            if (i > 0) GroupDivider()
            RequestRow(request, rows, onApprove = { onApprove(request) }, onDismiss = { onDismiss(request) })
            RefusalLine(rows.refusalFor(request.packageName))
        }
    }
}

/** What a dialog in [BridgePane] asks to confirm. */
private sealed interface BridgeConfirm {
    data class HandOver(
        val app: BridgeAccess.ApprovedApp,
    ) : BridgeConfirm

    data class Remove(
        val app: BridgeAccess.ApprovedApp,
    ) : BridgeConfirm

    data class Approve(
        val request: BridgeAccess.AccessRequest,
    ) : BridgeConfirm
}

@Composable
private fun BridgeConfirmDialog(
    asked: BridgeConfirm,
    driving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    when (asked) {
        is BridgeConfirm.HandOver ->
            CarlinkDialog(
                title = "Hand the adapter to ${asked.app.label}?",
                text = BridgeAccessText.handOverText(asked.app.label),
                confirmLabel = "Hand over",
                weight = DialogWeight.CAUTION,
                icon = Icons.Default.Usb,
                confirmEnabled = !driving,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
        is BridgeConfirm.Remove ->
            CarlinkDialog(
                title = "Remove ${asked.app.label}?",
                text = "${asked.app.label} can't use the adapter any more, and loses it now if it has it. It can ask again.",
                confirmLabel = "Remove",
                weight = DialogWeight.DANGER,
                icon = Icons.Default.Block,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
        is BridgeConfirm.Approve ->
            CarlinkDialog(
                title = "Approve ${asked.request.label}?",
                text =
                    "Package: ${asked.request.packageName}\nCertificate: ${asked.request.sha256}\n" +
                        "Installed by: ${asked.request.installer ?: "unknown (sideloaded)"}\n\n" +
                        "It can control the adapter while you hand it over. Approve only an app you trust.",
                confirmLabel = "Approve",
                weight = DialogWeight.CAUTION,
                icon = Icons.Default.VpnKey,
                confirmEnabled = !driving,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
    }
}

@Composable
private fun ApprovedRow(
    app: BridgeAccess.ApprovedApp,
    handedToIt: Boolean,
    rows: RowContext,
    onHandOver: () -> Unit,
    onRemove: () -> Unit,
) {
    val used = if (app.lastUsedAt > 0) "last used ${rows.clock(app.lastUsedAt)}" else "not used yet"
    val sessions = "${app.sessions} session${if (app.sessions == 1) "" else "s"}"
    SettingRow(
        title = app.label,
        subtitle = "${app.packageName}\n${BridgeAccessText.shortCert(app.sha256)} · ${BridgeAccessText.installerText(app.installer)} · $used · $sessions",
        icon = Icons.Default.Apps,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!handedToIt) RowAction("Hand over", onClick = onHandOver, enabled = !rows.driving)
            RowAction("Remove", onClick = onRemove, weight = ActionWeight.DANGER_OUTLINE)
        }
    }
}

@Composable
private fun RequestRow(
    request: BridgeAccess.AccessRequest,
    rows: RowContext,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val times = if (request.count == 1) "once" else "${request.count} times"
    Column {
        SettingRow(
            title = request.label,
            subtitle =
                "${request.packageName}\n${BridgeAccessText.shortCert(request.sha256)} · ${BridgeAccessText.installerText(request.installer)} · " +
                    "asked $times, last ${rows.clock(request.lastAt)}",
            icon = Icons.Default.VpnKey,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RowAction("Approve", onClick = onApprove, enabled = !rows.driving)
                RowAction("Dismiss", onClick = onDismiss)
            }
        }
        if (request.keyChanged) {
            Text(
                BridgeAccessText.KEY_CHANGED,
                style = SettingsStyle.RowSubtitle,
                color = SettingsStyle.ErrorText,
                modifier = Modifier.padding(start = 88.dp, end = 32.dp, bottom = 16.dp),
            )
        }
    }
}

/** The bridge's refusal of the row's last action, in its words. */
@Composable
private fun RefusalLine(text: String?) {
    if (text == null) return
    Text(text, style = SettingsStyle.RowSubtitle, color = SettingsStyle.ErrorText, modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 16.dp))
}

private const val HOLDER_ROW = "holder"
private const val PANE_ROW = ""
