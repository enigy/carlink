package com.carlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.CarlinkManager
import com.carlink.link.AdapterLink
import com.carlink.link.SetupOverNetwork
import com.carlink.link.WifiLinkSettings
import com.carlink.link.WifiLinkSetup
import com.carlink.logging.logInfo
import com.carlink.ocbm.WifiLinkProvisioning
import com.carlink.ui.components.CarlinkDialog
import com.carlink.ui.components.DialogWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the group asks the adapter for its `wifi_link` while it shows and a session is up. */
private const val INFO_POLL_MS = 3_000L

/**
 * Settings › Adapter Config and about › Adapter connection (the Wi-Fi link plan of 23 Nov): USB through the bridge, or Wi-Fi over
 * the iPhone's hotspot. Offered whether or not an adapter is connected (addendum 1: a driver with no bridge has no session
 * to read the capability from); the adapter's capability is checked once there is a session. The hotspot's name and
 * password go to the adapter over whatever session Carlink has ([com.carlink.link.WifiLinkSetup]); the password is hashed
 * first and kept nowhere. Typing and Send are for parked.
 */
@Composable
internal fun WifiLinkGroup(
    carlinkManager: CarlinkManager,
    live: SettingsLiveState,
) {
    val settings by carlinkManager.wifiLink.flow.collectAsState()
    val linkedOver by carlinkManager.linkedOver.collectAsState()
    val busy by carlinkManager.wifiSetup.busy.collectAsState()
    var outcome by remember { mutableStateOf<String?>(null) }
    var askRemove by remember { mutableStateOf(false) }
    val polling = live.box.adapterConnected && settings.wifiChosen
    LaunchedEffect(polling) {
        while (polling) {
            carlinkManager.refreshAdapterInfo()
            delay(INFO_POLL_MS)
        }
    }
    SettingsGroup {
        ChoiceSetting(
            title = WifiLinkText.TITLE,
            options = AdapterLink.entries,
            selected = settings.link,
            onSelect = {
                carlinkManager.wifiLink.setLink(it)
                logInfo("[UI_ACTION] Adapter connection: ${it.code}", tag = "UI")
            },
            subtitle = WifiLinkText.connectionLine(settings, linkedOver, live.bridgeInstalled),
            icon = Icons.Default.Wifi,
        )
        if (settings.wifiChosen) {
            GroupDivider()
            WifiSetUp(carlinkManager, live, settings, busy, outcome) { outcome = it }
        }
        if (settings.keyStored) {
            GroupDivider()
            SettingRow(title = WifiLinkText.REMOVE_TITLE, subtitle = WifiLinkText.REMOVE_SUBTITLE, icon = Icons.Default.LinkOff) {
                RowAction("Remove", enabled = !busy, weight = ActionWeight.CAUTION, onClick = { askRemove = true })
            }
        }
    }
    if (askRemove) RemoveDialog(carlinkManager, settings.ssid, live.bridgeInstalled, onDone = { askRemove = false }) { outcome = it }
}

/** The hotspot's fields, Send, and what the adapter and the last search say. */
@Suppress("LongParameterList") // the group's state, handed down whole
@Composable
private fun WifiSetUp(
    carlinkManager: CarlinkManager,
    live: SettingsLiveState,
    settings: WifiLinkSettings,
    busy: Boolean,
    outcome: String?,
    onOutcome: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val info by carlinkManager.wifiSetup.info.collectAsState()
    val miss by carlinkManager.wifiMiss.collectAsState()
    val linkedOver by carlinkManager.linkedOver.collectAsState()
    var ssid by remember(settings.ssid) { mutableStateOf(WifiLinkProvisioning.hotspotName(settings.ssid)) }
    var password by remember { mutableStateOf("") }
    HotspotFields(ssid, password, enabled = live.parked && !busy, onSsid = { ssid = it }, onPassword = { password = it })
    val name = ssid.trim()
    val problem = WifiLinkProvisioning.problem(name, password)
    val blocked = WifiLinkText.blocked(live.wifiLinkSupported, info.takeIf { live.box.adapterConnected })
    SettingRow(
        title = if (settings.setUp) "Send again" else "Send to the adapter",
        subtitle = if (busy) "Sending..." else outcome ?: WifiLinkText.readyLine(blocked, live.parked, problem),
        icon = Icons.Default.WifiTethering,
    ) {
        RowAction("Send", enabled = !busy && blocked == null && live.parked && problem == null, onClick = {
            onOutcome(null)
            scope.launch {
                val sent = carlinkManager.wifiSetup.send(name, password)
                password = ""
                onOutcome(WifiLinkText.sentLine(sent, name))
                logInfo("[UI_ACTION] Wi-Fi link sent: $sent", tag = "UI")
            }
        })
    }
    StateLines(WifiLinkText.stateLines(settings, info.takeIf { live.box.adapterConnected }, miss, linkedOver, live.bridgeInstalled))
    // M3: with no session to send over, the adapter's own set-up network.
    if (blocked == WifiLinkSetup.Sent.NO_SESSION) SetupNetworkRow(carlinkManager, name, password, enabled = live.parked && problem == null && !busy)
}

/** The set-up over the adapter's set-up network: join it, send the hotspot, let it go (parked only). */
@Composable
private fun SetupNetworkRow(
    carlinkManager: CarlinkManager,
    ssid: String,
    password: String,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    val phase by carlinkManager.setupNetwork.phase.collectAsState()
    val result by carlinkManager.setupNetwork.result.collectAsState()
    val confirm by carlinkManager.setupNetwork.confirm.collectAsState()
    val running = phase != SetupOverNetwork.Phase.IDLE && phase != SetupOverNetwork.Phase.DONE
    GroupDivider()
    SettingRow(title = WifiLinkText.SETUP_NETWORK_TITLE, subtitle = WifiLinkText.setupNetworkLine(phase, result, ssid), icon = Icons.Default.WifiTethering) {
        RowAction("Set up", enabled = enabled && !running, onClick = {
            scope.launch {
                val r = carlinkManager.setUpOverSetupNetwork(ssid, password)
                logInfo("[UI_ACTION] Set up over the adapter's Wi-Fi: $r", tag = "UI")
            }
        })
    }
    confirm?.let { asked -> SetupCodeDialog(asked) { match -> carlinkManager.setupNetwork.answer(match) } }
}

/**
 * The set-up's one question (the plan's ADDENDUM 5): the code large, where the adapter now shows it, and two answers. As
 * the pairing code's dialog, back or a tap outside answers nothing: a stray tap must not read as either. No answer in
 * 150 s sends nothing ([SetupOverNetwork]).
 */
@Composable
internal fun SetupCodeDialog(
    asked: SetupOverNetwork.Confirm,
    onAnswer: (Boolean) -> Unit,
) {
    CarlinkDialog(
        title = WifiLinkText.CODE_TITLE,
        text = WifiLinkText.codeText(asked.code, asked.networkName),
        confirmLabel = WifiLinkText.CODE_MATCH,
        dismissLabel = WifiLinkText.CODE_NO_MATCH,
        onConfirm = {
            logInfo("[UI_ACTION] Set-up code: they match", tag = "UI")
            onAnswer(true)
        },
        onDismiss = {
            logInfo("[UI_ACTION] Set-up code: they don't match", tag = "UI")
            onAnswer(false)
        },
        icon = Icons.Default.Bluetooth,
        dismissible = false,
    ) {
        Text(
            text = asked.code,
            // At a glance: as large as the pane's figures, digits of one width, a little apart.
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 80.sp, lineHeight = 88.sp, letterSpacing = 8.sp, fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        )
    }
}

@Composable
private fun RemoveDialog(
    carlinkManager: CarlinkManager,
    ssid: String,
    bridgeInstalled: Boolean,
    onDone: () -> Unit,
    onOutcome: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    CarlinkDialog(
        title = WifiLinkText.REMOVE_DIALOG_TITLE,
        text = WifiLinkText.removeDialogText(ssid, bridgeInstalled),
        confirmLabel = "Remove",
        weight = DialogWeight.CAUTION,
        icon = Icons.Default.LinkOff,
        onConfirm = {
            onDone()
            scope.launch {
                val removed = carlinkManager.wifiSetup.remove()
                onOutcome(WifiLinkText.removedLine(removed))
                logInfo("[UI_ACTION] Wi-Fi link removed: $removed", tag = "UI")
            }
        },
        onDismiss = onDone,
    )
}

/** The hotspot's name and password, typed on the head unit's keyboard, parked. */
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
        var shown by remember { mutableStateOf(false) }
        Text(WifiLinkText.HOTSPOT_TITLE, style = SettingsStyle.RowTitle)
        Text(WifiLinkText.HOTSPOT_BODY, style = SettingsStyle.RowSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = ssid,
            onValueChange = { onSsid(WifiLinkProvisioning.hotspotName(it).take(WifiLinkProvisioning.SSID_MAX_BYTES)) },
            label = { Text("Hotspot name") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        if ('\u2019' in ssid) {
            Text(WifiLinkText.APOSTROPHE_NOTE, style = SettingsStyle.RowSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = password,
            onValueChange = { onPassword(it.take(WifiLinkProvisioning.PASSPHRASE_MAX)) },
            label = { Text("Password") },
            singleLine = true,
            enabled = enabled,
            // Shown on request: a keyboard that turns a hyphen into a long dash can't be seen through the dots.
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { shown = !shown }) {
                    Icon(
                        if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (shown) "Hide the password" else "Show the password",
                    )
                }
            },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StateLines(lines: List<String>) {
    if (lines.isEmpty()) return
    Column(modifier = Modifier.padding(start = 88.dp, end = 32.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { Text(it, style = SettingsStyle.RowSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
