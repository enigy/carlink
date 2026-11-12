package com.carlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.logging.logInfo
import com.carlink.ocbm.OcbwLink
import com.carlink.ocbm.WifiLinkSettings
import kotlinx.coroutines.launch

/**
 * Settings › Adapter › Wi-Fi link: the adapter joins the car's hotspot and Carlink reaches it there instead of
 * through the USB bridge (OCBM `wifi-transport`). The hotspot's name and password go to the adapter over USB
 * once ([CarlinkManager.provisionWifi]), with a link key only this head unit and the adapter hold; the toggle
 * then picks Wi-Fi or USB for each new connection. The password is hashed before it leaves, and kept nowhere.
 */
@Composable
internal fun WifiLinkGroup(carlinkManager: CarlinkManager) {
    val settings by carlinkManager.wifiLink.flow.collectAsState()
    val link by carlinkManager.link.collectAsState()
    val scope = rememberCoroutineScope()
    var ssid by remember(settings.ssid) { mutableStateOf(settings.ssid) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    SettingsGroup {
        SwitchRow(
            title = "Connect over Wi-Fi",
            subtitle = toggleLine(settings, link),
            checked = settings.active,
            enabled = settings.provisioned && !busy,
            icon = Icons.Default.Wifi,
            onCheckedChange = {
                carlinkManager.wifiLink.setEnabled(it)
                logInfo("[UI_ACTION] Wi-Fi link toggle: ${if (it) "on" else "off"}", tag = "UI")
            },
        )
        GroupDivider()
        HotspotFields(ssid, password, enabled = !busy, onSsid = { ssid = it }, onPassword = { password = it })
        SettingRow(
            title = if (settings.provisioned) "Update the adapter" else "Send to the adapter",
            subtitle = message ?: sendLine(link == "USB", ssid, password),
            icon = Icons.Default.WifiTethering,
        ) {
            RowAction(
                if (busy) "Sending…" else "Send",
                enabled = link == "USB" && !busy && ssid.isNotBlank() && OcbwLink.provisionProblem(ssid.trim(), password) == null,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val problem = carlinkManager.provisionWifi(ssid.trim(), password)
                        password = ""
                        busy = false
                        message = problem ?: "Sent. The adapter restarts and joins \"${ssid.trim()}\"; Carlink reconnects over Wi-Fi."
                        logInfo("[UI_ACTION] Wi-Fi link sent: ${problem ?: "ok"}", tag = "UI")
                    }
                },
            )
        }
        if (settings.provisioned) {
            GroupDivider()
            RemoveRow(enabled = !busy) {
                busy = true
                scope.launch {
                    message = carlinkManager.clearWifi() ?: "Removed. The adapter is USB only again."
                    busy = false
                }
            }
        }
    }
}

/** The hotspot's name and password, typed on the head unit's keyboard. */
@Composable
private fun HotspotFields(
    ssid: String,
    password: String,
    enabled: Boolean,
    onSsid: (String) -> Unit,
    onPassword: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Car hotspot", style = SettingsStyle.RowTitle)
        Text(
            "The adapter joins this network. Sent over USB; the password is hashed first and not kept on this head unit.",
            style = SettingsStyle.RowSubtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = ssid,
            onValueChange = { onSsid(it.take(32)) },
            label = { Text("Network name (SSID)") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { onPassword(it.take(63)) },
            label = { Text("Password") },
            singleLine = true,
            enabled = enabled,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RemoveRow(
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    SettingRow(title = "Remove the Wi-Fi link", subtitle = "The adapter goes back to USB only.", icon = Icons.Default.LinkOff) {
        RowAction("Remove", enabled = enabled, weight = ActionWeight.CAUTION, onClick = onRemove)
    }
}

private fun toggleLine(
    s: WifiLinkSettings,
    link: String?,
): String =
    when {
        !s.provisioned -> "Send the car hotspot's name and password to the adapter first."
        link != null && link.startsWith("Wi-Fi") -> "Connected over $link."
        s.active -> "On: the next connection looks for the adapter on \"${s.ssid}\", then USB."
        else -> "Off: Carlink uses USB. Takes effect at the next connection."
    }

private fun sendLine(
    overUsb: Boolean,
    ssid: String,
    password: String,
): String =
    when {
        !overUsb -> "Connect to the adapter over USB to send these."
        ssid.isBlank() -> "Enter the hotspot's name."
        else -> OcbwLink.provisionProblem(ssid.trim(), password) ?: "Ready to send."
    }
