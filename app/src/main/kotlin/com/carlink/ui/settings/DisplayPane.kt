package com.carlink.ui.settings

import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.components.DialogSystemBars
import com.carlink.ui.components.OnSettingsHidden

/** Display modes in the order the pane lists them (two by two on a wide pane). */
private val MODE_ORDER =
    listOf(
        DisplayMode.SYSTEM_UI_VISIBLE,
        DisplayMode.STATUS_BAR_HIDDEN,
        DisplayMode.NAV_BAR_HIDDEN,
        DisplayMode.FULLSCREEN_IMMERSIVE,
    )

/**
 * Display: the four display modes as radio cards plus the loading-screen brand.
 *
 * Tapping a mode PREVIEWS it on the window at once, as the old dialog did; nothing is saved until
 * "Apply and restart CarPlay". Leaving the pane with a preview still showing puts back the bars the
 * window had before the first preview (read from the window itself, so a mode saved for the next
 * connection, but not yet applied, is never mistaken for what is on screen).
 */
@Composable
internal fun DisplayPane(
    savedMode: DisplayMode,
    onApply: (DisplayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = LocalActivity.current?.window
    var pending by remember(savedMode) { mutableStateOf(savedMode) }
    OnSettingsHidden { pending = savedMode } // the LaunchedEffect below then restores the bars
    val preview = remember(window) { BarPreview(window) }
    val latestSaved by rememberUpdatedState(savedMode)

    LaunchedEffect(pending) { if (pending == latestSaved) preview.restore() else preview.show(pending) }
    DisposableEffect(preview) { onDispose { preview.restore() } }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PaneHeader(title = "Display")
        SectionLabel("Display mode")
        ModeGrid(pending = pending, saved = savedMode, onPick = { pending = it })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Button(
                onClick = {
                    logInfo("[DISPLAY] User applied display mode: ${pending.name}", tag = "UI")
                    preview.forget()
                    onApply(pending)
                },
                enabled = pending != savedMode,
                modifier = Modifier.heightIn(min = 72.dp),
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(26.dp))
                Text("Apply and restart CarPlay", style = SettingsStyle.Action, modifier = Modifier.padding(start = 12.dp))
            }
            Text(
                "Tapping a mode previews it. With a phone connected, it applies at the next connection.",
                style = SettingsStyle.RowSubtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        SectionLabel("Loading screen", modifier = Modifier.padding(top = 8.dp))
        LoadingScreenChoice()
    }
}

@Composable
private fun ModeGrid(
    pending: DisplayMode,
    saved: DisplayMode,
    onPick: (DisplayMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        MODE_ORDER.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                pair.forEach { mode ->
                    ModeCard(mode, selected = mode == pending, current = mode == saved, onClick = { onPick(mode) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    mode: DisplayMode,
    selected: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = SettingsStyle.GroupShape,
        color = colors.surfaceContainer,
        border = BorderStroke(3.dp, if (selected) colors.primary else colors.surfaceContainer),
    ) {
        Row(modifier = Modifier.heightIn(min = 128.dp).padding(horizontal = 28.dp, vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RadioButton(selected = selected, onClick = null)
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(mode.label, style = SettingsStyle.RowTitle)
                    if (current) Text("IN USE", style = SettingsStyle.SectionLabel, color = colors.primary, modifier = Modifier.padding(start = 14.dp))
                }
                Text(mode.summary, style = SettingsStyle.RowSubtitle, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun LoadingScreenChoice() {
    val context = LocalContext.current
    val store = remember { BackgroundLogoStore.get(context) }
    val logo by store.flow.collectAsState()
    ChoiceRow(
        options = BackgroundLogoConfig.entries,
        selected = logo,
        onSelect = {
            logWarn("[UI_ACTION] Loading screen -> ${it.name}", tag = "UI")
            store.set(it)
        },
        label = { it.brandName },
        modifier = Modifier.widthIn(max = 760.dp),
    )
}

/** Shown when a display mode was saved but held back by a live phone session. */
@Composable
internal fun DisplayModeDeferredNotice(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Default.DisplaySettings, contentDescription = null) },
        title = {
            DialogSystemBars()
            Text("Display mode saved")
        },
        text = {
            Text(
                "A phone session is active, so the new display mode applies at the next connection. " +
                    "The current session keeps its layout.",
            )
        },
        confirmButton = { TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors()) { Text("OK") } },
    )
}

/**
 * Live bar preview on the activity window. [show] snapshots the bars actually visible before the
 * first preview; [restore] puts exactly those back; [forget] drops the snapshot once a mode is
 * applied, because MainActivity then owns the bars.
 */
private class BarPreview(
    private val window: Window?,
) {
    private var before: Pair<Boolean, Boolean>? = null

    fun show(mode: DisplayMode) {
        val w = window ?: return
        if (before == null) {
            val insets = ViewCompat.getRootWindowInsets(w.decorView) ?: return
            before = insets.isVisible(WindowInsetsCompat.Type.statusBars()) to insets.isVisible(WindowInsetsCompat.Type.navigationBars())
        }
        val controller = controller(w)
        if (mode.visibleBarTypes != 0) controller.show(mode.visibleBarTypes)
        if (mode.hiddenBarTypes != 0) controller.hide(mode.hiddenBarTypes)
    }

    fun restore() {
        val w = window ?: return
        val (status, nav) = before ?: return
        before = null
        val controller = controller(w)
        if (status) controller.show(WindowInsetsCompat.Type.statusBars()) else controller.hide(WindowInsetsCompat.Type.statusBars())
        if (nav) controller.show(WindowInsetsCompat.Type.navigationBars()) else controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    fun forget() {
        before = null
    }

    private fun controller(w: Window): WindowInsetsControllerCompat =
        WindowCompat.getInsetsController(w, w.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
}
