package com.carlink.ui.settings

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
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.CarlinkManager

/**
 * Adapter: the box's status in plain words (with the raw line kept for bug reports), how quickly the CarPlay picture
 * comes after the display ([ConnectTimeRow]), how Carlink reaches the adapter ([WifiLinkGroup]), the USB bridge's version
 * and other apps' access ([UsbBridgeGroup]), and the adapter's firmware ([InstallGroup]). The app's version, the privacy
 * policy and lyrics are under About ([AboutPane]); the log controls under Troubleshoot ([LogsGroup]).
 */
@Composable
internal fun AdapterPane(
    carlinkManager: CarlinkManager,
    live: SettingsLiveState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        PaneHeader(title = "Adapter Config")
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            // Left: the adapter and its connect time, then the USB bridge. Right: how Carlink reaches the adapter, then
            // its firmware. Each where it sat in Adapter and about; that pane's app and lyrics rows are About's (owner, 24 Nov).
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                AdapterStatusCard(carlinkManager.adapterName, live.box)
                SettingsGroup { ConnectTimeRow() }
                UsbBridgeGroup(live)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                WifiLinkGroup(carlinkManager, live)
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
