package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.carlink.CarlinkManager
import com.carlink.ocbm.GoBackResult
import com.carlink.ocbm.InstallMode
import com.carlink.ocbm.OneFileInstall
import com.carlink.ocbm.UpdateOffer
import com.carlink.ocbm.UpdatePhase
import com.carlink.ui.components.GoBackToStockDialog
import com.carlink.ui.components.LocalSettingsVisible
import com.carlink.ui.components.RoundTripDialog
import com.carlink.ui.components.UpdateDialog
import com.carlink.ui.components.installLabel
import com.carlink.ui.components.updateLine
import com.carlink.ui.components.updateTitle

/**
 * Settings › Adapter for an OCBM one-file install (handoff 10-28, plan page's mockup): the adapter's
 * firmware and a newer OCBM to update it to, whether stock CarPlay is still kept, and the way back to it
 * while it is. Shown on screen, the way back is also what lets the trial be confirmed
 * ([com.carlink.ocbm.InstallSession.wayBackShown]).
 */
@Composable
internal fun InstallGroup(carlinkManager: CarlinkManager) {
    val session = carlinkManager.oneFileInstall
    val install by session.install.collectAsState()
    val goBack by session.goBack.collectAsState()
    val updating by carlinkManager.adapterUpdate.phase.collectAsState()
    val current = install
    if (current == null) {
        // The adapter restarts during an update: its progress stays on screen until the adapter is back.
        if (updating != UpdatePhase.Idle) SettingsGroup { UpdateRow(carlinkManager, null, divider = false) }
        return
    }
    val visible = LocalSettingsVisible.current
    var asking by remember { mutableStateOf(false) }
    // Settings is kept composed while closed, so only a page actually on screen counts as showing it.
    LaunchedEffect(visible, current.mode) { if (visible && current.mode.stockKept) session.wayBackShown() }
    SettingsGroup {
        SettingRow(title = "Adapter firmware", icon = Icons.Default.Memory) {
            Text(installLabel(current), style = SettingsStyle.RowTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        UpdateRow(carlinkManager, current)
        GroupDivider()
        SettingRow(title = "Stock CarPlay", icon = Icons.Default.SettingsBackupRestore) {
            Text(stockLine(current.mode), style = SettingsStyle.RowTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (current.mode.stockKept) {
            GroupDivider()
            SettingRow(title = "Go back to stock CarPlay", subtitle = goBackLine(goBack), icon = Icons.AutoMirrored.Filled.Undo) {
                RowAction("Go back", onClick = { asking = true }, enabled = goBack != GoBackResult.GOING, weight = ActionWeight.DANGER_OUTLINE)
            }
        }
    }
    if (asking) {
        GoBackToStockDialog(
            onGoBack = {
                asking = false
                session.goBackToStock()
            },
            onDismiss = { asking = false },
        )
    }
}

/**
 * A newer OCBM for the adapter (remote updates plan, U2): "OCBM 1.1.0 available" with its notes and Update, which
 * asks once more in a dialog; then the update's progress and outcome. Nothing shows with nothing to offer.
 */
@Composable
private fun UpdateRow(
    carlinkManager: CarlinkManager,
    install: OneFileInstall?,
    divider: Boolean = true,
) {
    val update = carlinkManager.adapterUpdate
    val offer by update.offer.collectAsState()
    val phase by update.phase.collectAsState()
    var asking by remember(offer) { mutableStateOf(false) }
    var blocked by remember(offer, phase) { mutableStateOf<String?>(null) }
    if (offer == UpdateOffer.None && phase == UpdatePhase.Idle) return
    if (divider) GroupDivider()
    SettingRow(title = updateTitle(offer, phase), subtitle = blocked ?: updateLine(offer, phase), icon = Icons.Default.SystemUpdate) {
        when {
            phase is UpdatePhase.Updating -> Unit
            phase != UpdatePhase.Idle -> RowAction("OK", onClick = update::dismiss)
            offer is UpdateOffer.InPlace || offer is UpdateOffer.RoundTrip ->
                RowAction("Update", onClick = {
                    blocked = update.notNowReason()
                    asking = blocked == null
                })
        }
    }
    if (!asking) return
    val dismiss = { asking = false }
    when (val o = offer) {
        is UpdateOffer.InPlace ->
            UpdateDialog(o, install?.version, onDismiss = dismiss, onUpdate = {
                asking = false
                update.update()
            })
        is UpdateOffer.RoundTrip ->
            RoundTripDialog(o, onDismiss = dismiss, onGoBack = {
                asking = false
                carlinkManager.oneFileInstall.goBackToStock()
            })
        else -> Unit
    }
}

private fun stockLine(mode: InstallMode): String =
    when (mode) {
        InstallMode.TRIAL -> "Kept until this drive ends"
        InstallMode.CONFIRMED -> "Removed when this drive ends"
        InstallMode.FINAL -> "Removed"
    }

private fun goBackLine(result: GoBackResult?): String? =
    when (result) {
        GoBackResult.GOING -> "Restarting on stock CarPlay, about 30 seconds."
        GoBackResult.REFUSED -> "The adapter has no stock CarPlay to go back to."
        GoBackResult.NO_ANSWER -> "The adapter did not answer. Try again."
        null -> null
    }
