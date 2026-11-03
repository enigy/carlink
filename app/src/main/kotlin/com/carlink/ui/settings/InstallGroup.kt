package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.carlink.CarlinkManager
import com.carlink.ocbm.GoBackResult
import com.carlink.ocbm.InstallMode
import com.carlink.ocbm.OneFileInstall
import com.carlink.ocbm.StockRemoval
import com.carlink.ocbm.UpdateOffer
import com.carlink.ocbm.UpdatePhase
import com.carlink.ui.components.FinishFirstDialog
import com.carlink.ui.components.GoBackToStockDialog
import com.carlink.ui.components.RemoveStockDialog
import com.carlink.ui.components.UpdateDialog
import com.carlink.ui.components.installLabel
import com.carlink.ui.components.updateLine
import com.carlink.ui.components.updateTitle

/**
 * Settings › Adapter for an OCBM one-file install (handoff 10-28, plan page's mockup): the adapter's
 * firmware and a newer OCBM to update it to, whether stock CarPlay is still kept, and the way back to it
 * while it is. Showing the way back confirms nothing: the install finishes only when the driver agrees to
 * for an update (Scott, 1 Nov).
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
    var asking by remember { mutableStateOf(false) }
    SettingsGroup {
        // The values sit under the titles: as trailing text they were measured first, and in the right-hand column of
        // the 2400x960 screen they left "Adapte…" and "…" of the titles (bench 11-03).
        SettingRow(title = "Adapter firmware", subtitle = installLabel(current), icon = Icons.Default.Memory)
        UpdateRow(carlinkManager, current)
        GroupDivider()
        SettingRow(title = "Stock CarPlay", subtitle = stockLine(current.mode), icon = Icons.Default.SettingsBackupRestore)
        if (carlinkManager.offersStockRemoval(current)) RemoveStockRow(carlinkManager)
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
 * "Remove stock CarPlay" (Scott, 2 Nov): a trial that has passed its self-test and carried CarPlay can be finished on
 * purpose, so later updates have the room. Remove checks the trial, then asks once more; a trial that hasn't proven
 * itself says why, and nothing is sent.
 */
@Composable
private fun RemoveStockRow(carlinkManager: CarlinkManager) {
    val removal = carlinkManager.stockRemoval
    val state by removal.state.collectAsState()
    GroupDivider()
    SettingRow(title = "Remove stock CarPlay", subtitle = removeStockLine(state), icon = Icons.Default.DeleteSweep) {
        when (state) {
            StockRemoval.Idle -> RowAction("Remove", onClick = removal::ask, weight = ActionWeight.DANGER_OUTLINE)
            is StockRemoval.NotNow, is StockRemoval.Failed -> RowAction("OK", onClick = removal::dismiss)
            StockRemoval.Working, StockRemoval.Asking -> Unit
        }
    }
    if (state == StockRemoval.Asking) RemoveStockDialog(onRemove = removal::remove, onDismiss = removal::dismiss)
}

private fun removeStockLine(state: StockRemoval): String =
    when (state) {
        is StockRemoval.NotNow -> state.reason
        is StockRemoval.Failed -> state.reason
        StockRemoval.Working -> "Checking the adapter"
        StockRemoval.Idle, StockRemoval.Asking -> "Frees the room for updates. The USB stick's backup is then the way back."
    }

/**
 * A newer OCBM for the adapter (remote updates plan, U2): "OCBM 1.1.0 available" with its notes and Update, which
 * asks once more in a dialog; then the update's progress and outcome. A trial adapter without the room asks to
 * finish its install first (Scott, 1 Nov). Nothing shows with nothing to offer.
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
    val finishAsk by update.finishAsk.collectAsState()
    var asking by remember(offer) { mutableStateOf(false) }
    var blocked by remember(offer, phase) { mutableStateOf<String?>(null) }
    if (offer == UpdateOffer.None && phase == UpdatePhase.Idle) return
    if (divider) GroupDivider()
    SettingRow(title = updateTitle(offer, phase), subtitle = blocked ?: updateLine(offer, phase), icon = Icons.Default.SystemUpdate) {
        when {
            phase is UpdatePhase.Updating -> Unit
            phase != UpdatePhase.Idle -> RowAction("OK", onClick = update::dismiss)
            offer is UpdateOffer.InPlace ->
                RowAction("Update", onClick = {
                    blocked = update.notNowReason()
                    asking = blocked == null
                })
            // The session checks the trial, then asks (finishAsk) or says why not.
            offer is UpdateOffer.FinishFirst -> RowAction("Update", onClick = update::askToFinish)
        }
    }
    finishAsk?.let { FinishFirstDialog(it, onFinish = update::finish, onDecline = update::declineFinish) }
    val o = offer
    if (asking && o is UpdateOffer.InPlace) {
        UpdateDialog(o, install?.version, onDismiss = { asking = false }, onUpdate = {
            asking = false
            update.update()
        })
    }
}

private fun stockLine(mode: InstallMode): String =
    when (mode) {
        InstallMode.TRIAL -> "Kept as a fallback"
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
