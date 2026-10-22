package com.carlink.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.MainActivity
import com.carlink.ui.components.CarlinkDialog
import com.carlink.ui.components.DialogWeight
import com.carlink.ui.components.OnSettingsHidden
import com.carlink.ui.settings.ConnectedPhone
import com.carlink.ui.settings.SettingsStyle
import com.carlink.ui.theme.LocalCarlinkColors
import kotlinx.coroutines.launch

/** Back, the title, which phone is connected, and Close app. */
@Composable
internal fun SettingsTopBar(
    phone: ConnectedPhone?,
    onBack: () -> Unit,
    onCloseApp: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 120.dp).padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        FilledTonalIconButton(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onBack()
            },
            modifier = Modifier.size(72.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(32.dp))
        }
        Text("Carlink settings", style = SettingsStyle.PaneTitle, fontSize = 36.sp, modifier = Modifier.weight(1f))
        PhonePill(phone)
        CloseAppButton(onCloseApp)
    }
}

@Composable
private fun PhonePill(phone: ConnectedPhone?) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (phone != null) SettingsStyle.ConnectedFill else colors.surfaceContainer,
        contentColor = if (phone != null) colors.onPrimaryContainer else colors.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(start = 18.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.PhoneIphone, contentDescription = null, modifier = Modifier.size(26.dp))
            Text(phone?.let { "${it.name} · ${it.kind}" } ?: "No phone connected", fontSize = 21.sp, maxLines = 1)
        }
    }
}

/**
 * Close app with its confirmation; [onCloseApp] tears the session down (a suspend call — it runs
 * off the main thread), and the Activity finishes once it has returned, so onDestroy's own
 * synchronous `release()` never overlaps a teardown still in flight.
 */
@Composable
private fun CloseAppButton(onCloseApp: suspend () -> Unit) {
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var showConfirm by remember { mutableStateOf(false) }
    OnSettingsHidden { showConfirm = false }
    var closing by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { showConfirm = true },
        modifier = Modifier.heightIn(min = 72.dp),
        border = BorderStroke(2.dp, LocalCarlinkColors.current.closeAppBorder),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = LocalCarlinkColors.current.closeAppFill, contentColor = SettingsStyle.ErrorText),
    ) {
        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(28.dp))
        Text("Close app", style = SettingsStyle.Action, modifier = Modifier.padding(start = 12.dp))
    }
    if (showConfirm) {
        CarlinkDialog(
            title = "Close app?",
            text = "This will stop all connections and close the app.",
            confirmLabel = "Close",
            onConfirm = {
                closing = true
                scope.launch {
                    try {
                        onCloseApp()
                    } finally {
                        // Also ends the hidden cluster task, which finishAffinity() alone left over the launcher.
                        (activity as? MainActivity)?.closeApp() ?: activity?.finishAffinity()
                    }
                }
            },
            onDismiss = { showConfirm = false },
            icon = Icons.Default.PowerSettingsNew,
            weight = DialogWeight.DANGER,
            confirmEnabled = !closing,
        )
    }
}
