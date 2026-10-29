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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.CarlinkManager
import com.carlink.logging.logWarn

/**
 * Adapter and about: the box's status in plain words (with the raw line kept for bug reports) and
 * the app version. The log controls live under Troubleshoot ([LogsGroup]).
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
                SettingsGroup {
                    SettingRow(title = "App version", icon = Icons.Default.Info) {
                        Text(rememberAppVersion(), style = SettingsStyle.RowTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                InstallGroup(carlinkManager)
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
private fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName ?: "- - -" // "2.00": the build number stays out of About (logs keep it)
        } catch (e: PackageManager.NameNotFoundException) {
            logWarn("[Settings] Failed to get package info: ${e.message}", tag = "UI")
            "- - -"
        }
    }
}
