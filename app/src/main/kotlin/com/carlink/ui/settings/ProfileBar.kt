package com.carlink.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.carlink.logging.logWarn
import com.carlink.ui.components.CarlinkDialog
import com.carlink.ui.components.DialogWeight
import com.carlink.ui.components.OnSettingsHidden

/** Nothing picked yet, for a choice row whose selection may be empty. */
private val NO_PROFILE = DriverProfiles.Profile("", "")

private enum class ProfileDialog { COPY, RESET }

/**
 * Settings › Cluster, top: whose Cluster and Driving settings these are (driver profiles phase 2,
 * 2026-10-23). While parked, another iPhone's profile can be shown and edited, one copied over
 * another, or one reset to factory defaults. Driving puts Settings back on the profile in use
 * (SettingsScreen). [onRewritten] runs after a copy or reset.
 */
@Composable
internal fun ProfileBar(
    view: DriverProfiles.View,
    connectedMac: String?,
    driving: Boolean,
    onRewritten: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }
    OnSettingsHidden { dialog = null }
    val shown = view.shown
    val several = view.all.size > 1
    SettingsGroup(modifier = modifier) {
        SettingRow(
            title = shown?.let { "Settings for ${it.label}" } ?: "Settings for every phone",
            subtitle = subtitle(view, connectedMac, driving),
            icon = Icons.Default.PhoneIphone,
            // Amber while showing an iPhone that is not the one in use.
            tint = if (view.other != null) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
        ) {
            if (shown != null) {
                if (several) RowAction(label = "Copy from", enabled = !driving, onClick = { dialog = ProfileDialog.COPY })
                RowAction(label = "Reset", enabled = !driving, weight = ActionWeight.CAUTION, onClick = { dialog = ProfileDialog.RESET })
            }
        }
        if (several && !driving) ProfilePicker(view)
        val open = dialog
        if (open != null && shown != null && !driving) {
            ProfileBarDialog(open, shown, view.all, onClose = { dialog = null }, onRewritten = onRewritten)
        }
    }
}

private fun subtitle(
    view: DriverProfiles.View,
    connectedMac: String?,
    driving: Boolean,
): String = view.describe(connectedMac) + if (driving && view.shown != null) " Switch, copy and reset while parked." else ""

/** Which iPhone's settings the rows below show; the one in use is marked. */
@Composable
private fun ProfilePicker(view: DriverProfiles.View) {
    val context = LocalContext.current
    Column {
        GroupDivider()
        ChoiceRow(
            options = view.all,
            selected = view.shown ?: NO_PROFILE,
            onSelect = {
                logWarn("[UI_ACTION] Profile shown -> ${it.mac}", tag = "UI")
                DriverProfiles.edit(context, it.mac)
            },
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
            label = { p -> if (p.mac == view.active?.mac) "${p.label} (in use)" else p.label },
        )
    }
}

@Composable
private fun ProfileBarDialog(
    dialog: ProfileDialog,
    shown: DriverProfiles.Profile,
    all: List<DriverProfiles.Profile>,
    onClose: () -> Unit,
    onRewritten: () -> Unit,
) {
    val context = LocalContext.current
    when (dialog) {
        ProfileDialog.COPY ->
            CopyProfileDialog(shown, all.filter { it.mac != shown.mac }, onDismiss = onClose) { from ->
                logWarn("[UI_ACTION] Profile copy ${from.mac} -> ${shown.mac}", tag = "UI")
                DriverProfiles.copy(context, from.mac, shown.mac)
                onClose()
                onRewritten()
            }
        ProfileDialog.RESET ->
            CarlinkDialog(
                title = "Reset ${shown.label}?",
                text = "Back to factory settings: directions on cluster, lane guidance on, turn alert off, CarPlay limited while driving.",
                confirmLabel = "Reset",
                onConfirm = {
                    logWarn("[UI_ACTION] Profile reset ${shown.mac}", tag = "UI")
                    DriverProfiles.reset(context, shown.mac)
                    onClose()
                    onRewritten()
                },
                onDismiss = onClose,
                icon = Icons.Default.RestartAlt,
                weight = DialogWeight.CAUTION,
            )
    }
}

/** Pick the iPhone whose settings replace [to]'s. */
@Composable
private fun CopyProfileDialog(
    to: DriverProfiles.Profile,
    from: List<DriverProfiles.Profile>,
    onDismiss: () -> Unit,
    onCopy: (DriverProfiles.Profile) -> Unit,
) {
    var pick by remember { mutableStateOf(from.singleOrNull()) }
    CarlinkDialog(
        title = "Copy settings to ${to.label}",
        text = "Its turn alert, lane guidance, directions on cluster and CarPlay limit are replaced by the ones from:",
        confirmLabel = "Copy",
        onConfirm = { pick?.let(onCopy) },
        onDismiss = onDismiss,
        icon = Icons.Default.PhoneIphone,
        confirmEnabled = pick != null,
    ) {
        ChoiceRow(options = from, selected = pick ?: NO_PROFILE, onSelect = { pick = it }, modifier = Modifier.padding(top = 24.dp))
    }
}
