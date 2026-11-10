package com.carlink.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carlink.logging.logWarn
import com.carlink.ui.components.CarlinkDialog
import com.carlink.ui.components.DialogWeight
import com.carlink.usb.BridgeUpdateOffer
import com.carlink.usb.BridgeUpdateStep
import com.carlink.usb.PinnedBridge

/**
 * Adapter and about › USB bridge, below the installed version: the bridge this Carlink pins when it is newer. For a
 * bridge of API 7 or later, Update (parked only) behind a confirm, then each step and the outcome in the row; for an
 * older one, or where the head unit won't let the bridge install apps, the manual route. Nothing at all when the bridge
 * is current.
 */
@Suppress("LongParameterList") // what Settings shows, and the three things the owner can do from it
@Composable
internal fun BridgeUpdateRows(
    offer: BridgeUpdateOffer,
    step: BridgeUpdateStep,
    driving: Boolean,
    onUpdate: (PinnedBridge) -> Unit,
    // Null where the head unit has no install-apps page for the bridge.
    onAllowInstalls: (() -> Unit)?,
    onOpenFiles: () -> Boolean,
) {
    when (offer) {
        BridgeUpdateOffer.None -> StepLine(step)
        is BridgeUpdateOffer.SelfUpdate -> {
            SelfUpdateRows(offer.pin, step, driving, onUpdate, onAllowInstalls)
            // Not allowed, and no page to allow it from: the manual route is the way left.
            if (step == BridgeUpdateStep.NotAllowed && onAllowInstalls == null) HowToUpdate(offer.pin, driving, onOpenFiles, blocked = true)
        }
        is BridgeUpdateOffer.Manual -> {
            GroupDivider()
            HowToUpdate(offer.pin, driving, onOpenFiles, blocked = false)
        }
    }
}

@Composable
private fun SelfUpdateRows(
    pin: PinnedBridge,
    step: BridgeUpdateStep,
    driving: Boolean,
    onUpdate: (PinnedBridge) -> Unit,
    onAllowInstalls: (() -> Unit)?,
) {
    var confirm by remember { mutableStateOf(false) }
    GroupDivider()
    SettingRow(title = BridgeUpdateText.available(pin), icon = Icons.Default.SystemUpdate) {
        RowAction("Update", onClick = { confirm = true }, enabled = BridgeUpdateText.updateEnabled(driving, step))
    }
    if (step == BridgeUpdateStep.NotAllowed) {
        Line(if (onAllowInstalls != null) BridgeUpdateText.NOT_ALLOWED else BridgeUpdateText.NOT_ALLOWED_NO_PAGE, error = true)
        if (onAllowInstalls != null) SettingRow(title = "Let the bridge install apps") { RowAction("Allow", onClick = onAllowInstalls, enabled = !driving) }
    } else {
        StepLine(step)
    }
    if (confirm) {
        CarlinkDialog(
            title = BridgeUpdateText.CONFIRM_TITLE,
            text = BridgeUpdateText.confirmText(pin),
            confirmLabel = "Update bridge",
            weight = DialogWeight.CAUTION,
            icon = Icons.Default.SystemUpdate,
            // Parked only: a drive starting under the dialog greys it.
            confirmEnabled = !driving,
            onConfirm = {
                confirm = false
                onUpdate(pin)
            },
            onDismiss = { confirm = false },
        )
    }
}

/** The manual route: for a bridge too old to update itself, or on a head unit that won't let it ([blocked]). */
@Composable
private fun HowToUpdate(
    pin: PinnedBridge,
    driving: Boolean,
    onOpenFiles: () -> Boolean,
    blocked: Boolean,
) {
    var show by remember { mutableStateOf(false) }
    var filesFailed by remember { mutableStateOf(false) }
    if (blocked) {
        SettingRow(title = BridgeUpdateText.BLOCKED_ROW) { RowAction("How to update", onClick = { show = true }) }
    } else {
        SettingRow(title = BridgeUpdateText.available(pin), icon = Icons.Default.SystemUpdate) {
            RowAction("How to update", onClick = { show = true })
        }
    }
    if (filesFailed) Line("This head unit's Files app couldn't be opened. Install the bridge with adb instead.", error = true)
    if (show) {
        CarlinkDialog(
            title = BridgeUpdateText.MANUAL_TITLE,
            text = if (blocked) BridgeUpdateText.blockedManualText(pin) else BridgeUpdateText.manualText(pin),
            confirmLabel = "Open Files",
            icon = Icons.Default.SystemUpdate,
            // Files isn't distraction-optimized: GM covers it while driving.
            confirmEnabled = !driving,
            onConfirm = {
                show = false
                filesFailed = !onOpenFiles()
                if (filesFailed) logWarn("[BRIDGE_UPDATE] Files couldn't be opened for the manual update", tag = "UI")
            },
            onDismiss = { show = false },
        )
    }
}

@Composable
private fun StepLine(step: BridgeUpdateStep) {
    BridgeUpdateText.stepLine(step)?.let { Line(it, error = step is BridgeUpdateStep.Failed) }
}

@Composable
private fun Line(
    text: String,
    error: Boolean,
) {
    Text(
        text,
        style = SettingsStyle.RowSubtitle,
        color = if (error) SettingsStyle.ErrorText else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 16.dp),
    )
}
