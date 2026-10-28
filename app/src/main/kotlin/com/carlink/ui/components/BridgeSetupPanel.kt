package com.carlink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.carlink.ui.theme.AutomotiveDimens

/** Where the bridge APK is published; shown so a new user can put it on a USB drive. */
private const val BRIDGE_APK_LOCATION = "github.com/enigy/carlink → app/carlink_bridge.apk, or Releases"

private val TextColor = Color(0xFFDDE4E5) // the overlay's fixed light text colour

/**
 * Shown on the connecting screen in place of the status words while the USB bridge
 * (android.car.usb.handler) isn't installed (Carlink2 [210]). Carlink has no USB access of its own,
 * so without the bridge it can't reach the adapter at all; this says so and offers the head unit's
 * built-in Files app to install it from a USB drive. MainActivity re-checks on resume, so the panel goes away once
 * the user comes back.
 */
@Composable
fun BridgeSetupPanel(onOpenFiles: () -> Boolean) {
    SetupPanel(
        SetupPanelSpec(
            title = "USB bridge not installed",
            body =
                "Carlink needs its USB bridge to reach the adapter, and to show turn icons on the cluster. " +
                    "To install it while parked:\n" +
                    "1. Copy the bridge APK to a USB drive ($BRIDGE_APK_LOCATION).\n" +
                    "2. Plug the drive into a free USB port.\n" +
                    "3. Tap Open Files, open the drive, and tap the APK. If asked, allow Files to install apps.\n" +
                    "4. When it's installed, tap the Carlink notification to come back (or press Home and open Carlink).",
            buttonLabel = "Open Files",
            buttonIcon = Icons.Default.FolderOpen,
            failureText = "This head unit's Files app couldn't be opened. Install the bridge with adb instead.",
        ),
        onClick = onOpenFiles,
    )
}

/**
 * Shown instead while the bridge is installed but isn't answering (Carlink2 [211]): bind failed,
 * out of date, or it couldn't open the adapter. Carlink keeps retrying underneath.
 */
@Composable
fun BridgeNotStartingPanel(onOpenAppInfo: () -> Boolean) {
    SetupPanel(
        SetupPanelSpec(
            title = "USB bridge isn't starting",
            body =
                "The USB bridge is installed but isn't answering Carlink, which keeps retrying.\n" +
                    "Tap Open App info, then Force stop, and come back here. If the bridge is older than this app, " +
                    "install the bridge APK from the same release.\n" +
                    "If it still doesn't connect, restart the head unit.",
            buttonLabel = "Open App info",
            buttonIcon = Icons.Default.Info,
            failureText = "Settings has no App info page for the bridge. Restart the head unit.",
        ),
        onClick = onOpenAppInfo,
    )
}

/** The words and the one button of a setup panel. */
private data class SetupPanelSpec(
    val title: String,
    val body: String,
    val buttonLabel: String,
    val buttonIcon: ImageVector,
    val failureText: String,
)

@Composable
private fun SetupPanel(
    spec: SetupPanelSpec,
    onClick: () -> Boolean,
) {
    var failed by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .widthIn(max = 960.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(24.dp))
                .padding(32.dp),
    ) {
        Text(text = spec.title, style = MaterialTheme.typography.headlineMedium, color = TextColor)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = spec.body, style = MaterialTheme.typography.bodyLarge, color = TextColor)
        Spacer(modifier = Modifier.height(24.dp))
        FilledTonalButton(
            onClick = { failed = !onClick() },
            modifier = Modifier.height(AutomotiveDimens.ButtonMinHeight),
            contentPadding = PaddingValues(horizontal = AutomotiveDimens.ButtonPaddingHorizontal, vertical = AutomotiveDimens.ButtonPaddingVertical),
        ) {
            Icon(imageVector = spec.buttonIcon, contentDescription = null, modifier = Modifier.size(AutomotiveDimens.IconSize))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = spec.buttonLabel, style = MaterialTheme.typography.titleLarge)
        }
        if (failed) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = spec.failureText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
    }
}
