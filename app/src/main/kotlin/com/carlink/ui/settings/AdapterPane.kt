package com.carlink.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.CarlinkManager
import com.carlink.logging.VerboseLogging
import com.carlink.logging.logWarn
import kotlinx.coroutines.launch

/**
 * Adapter and about: the box's status in plain words (with the raw line kept for bug reports), the
 * log controls, and the app version.
 *
 * Save Logs pulls the box's RAM-only logs over the live link (they are gone at power-off), so it
 * needs a connected adapter. Detailed logging lifts the release build's WARN-only filter.
 */
@Composable
internal fun AdapterPane(
    carlinkManager: CarlinkManager,
    live: SettingsLiveState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        PaneHeader(title = "Adapter and about")
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            AdapterStatusCard(carlinkManager.adapterName, live.box, Modifier.weight(1f))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                LogsGroup(carlinkManager, live)
                SettingsGroup {
                    SettingRow(title = "App version", icon = Icons.Default.Info) {
                        Text(rememberAppVersion(), style = SettingsStyle.RowTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdapterStatusCard(
    adapterName: String,
    box: BoxSnapshot,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    SettingsGroup(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Surface(modifier = Modifier.size(72.dp), shape = RoundedCornerShape(22.dp), color = colors.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Memory, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(36.dp))
                    }
                }
                Column {
                    Text(adapterName, style = SettingsStyle.PaneTitle)
                    Text(SettingsModel.adapterMode(box), style = SettingsStyle.RowSubtitle, color = colors.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsModel.healthItems(box).forEach { HealthChip(it) }
            }
            if (box.raw.isNotEmpty()) {
                Column {
                    Text("Technical status", style = SettingsStyle.RowSubtitle, color = colors.onSurfaceVariant)
                    Text(box.raw, fontFamily = FontFamily.Monospace, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun LogsGroup(
    carlinkManager: CarlinkManager,
    live: SettingsLiveState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    SettingsGroup {
        SwitchRow(
            title = "Detailed logging",
            subtitle = "More detail in saved logs",
            checked = live.verboseLogs,
            icon = Icons.Default.Description,
            onCheckedChange = { on ->
                live.verboseLogs = on
                logWarn("[UI_ACTION] Verbose Logs -> ${if (on) "on" else "off"}", tag = "UI")
                VerboseLogging.setEnabled(context, on)
            },
        )
        GroupDivider()
        SettingRow(
            title = "Save logs",
            subtitle = result ?: "App and adapter logs for a bug report",
            icon = Icons.Default.Save,
        ) {
            RowAction(
                label = if (saving) "Saving…" else "Save",
                enabled = live.box.adapterConnected && !saving,
                onClick = {
                    logWarn("[UI_ACTION] Save Logs button clicked", tag = "UI")
                    saving = true
                    result = null
                    scope.launch {
                        result =
                            carlinkManager.saveLogs().fold(
                                onSuccess = { "Saved to $it" },
                                onFailure = { "Could not save logs: ${it.message}" },
                            )
                        saving = false
                    }
                },
            )
        }
    }
}

@Composable
private fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName}+${pi.longVersionCode}"
        } catch (e: PackageManager.NameNotFoundException) {
            logWarn("[Settings] Failed to get package info: ${e.message}", tag = "UI")
            "- - -"
        }
    }
}
