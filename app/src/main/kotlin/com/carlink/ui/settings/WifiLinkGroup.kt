package com.carlink.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SignalWifiConnectedNoInternet4
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.CarlinkManager
import com.carlink.link.AdapterLink
import com.carlink.link.ApBand
import com.carlink.link.LinkMode
import com.carlink.link.SetupOverNetwork
import com.carlink.link.WifiLinkSettings
import com.carlink.link.WifiLinkSetup
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.components.CarlinkDialog
import com.carlink.ui.components.DialogWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How often the group asks the adapter for its `wifi_link` while it shows and a session is up. */
private const val INFO_POLL_MS = 3_000L

/**
 * Settings › Adapter Config › Adapter connection (the Wi-Fi link plan of 23 Nov, and its ADDENDUM 24): Wi-Fi, USB direct
 * or USB bridge. Wi-Fi is the AP link: the head unit joins the adapter's own Wi-Fi network. Its set-up sends this head
 * unit's link key and the band picked here to the adapter over whatever session Carlink has
 * ([com.carlink.link.WifiLinkSetup.sendAp]), or with none over the adapter's set-up network, and shows the name and
 * password of the adapter's Wi-Fi with the steps to join it in the car's Wi-Fi settings. Offered whether or not an adapter
 * is connected (addendum 1: a driver with no bridge has no session to read the capability from); the adapter's capability
 * is checked once there is a session. Set-up and the password are for parked. A driver who never chose Wi-Fi sees the
 * choice and its line, as before, and nothing else.
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
            AdapterWifiSetUp(carlinkManager, live, settings, busy, outcome) { outcome = it }
        }
        if (settings.keyStored) {
            GroupDivider()
            val subtitle = if (settings.mode == LinkMode.AP) ApLinkText.REMOVE_SUBTITLE else WifiLinkText.REMOVE_SUBTITLE
            SettingRow(title = WifiLinkText.REMOVE_TITLE, subtitle = subtitle, icon = Icons.Default.LinkOff) {
                RowAction("Remove", enabled = !busy, weight = ActionWeight.CAUTION, onClick = { askRemove = true })
            }
        }
    }
    if (askRemove) RemoveDialog(carlinkManager, settings, live.bridgeInstalled, onDone = { askRemove = false }) { outcome = it }
}

/**
 * The AP link (ADDENDUM 24): what it is, the band, Set up, then the adapter's network to join and what the adapter and
 * the last search say; with no session to set up through, the adapter's own set-up network.
 */
@Suppress("LongParameterList") // the group's state, handed down whole
@Composable
private fun AdapterWifiSetUp(
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
    val adapterInfo = info.takeIf { live.box.adapterConnected }
    var askBand by remember { mutableStateOf(false) }
    Intro(ApLinkText.TITLE, ApLinkText.BODY)
    ChoiceSetting(
        title = ApLinkText.BAND_TITLE,
        options = ApBand.entries,
        selected = settings.band,
        onSelect = {
            carlinkManager.wifiLink.setBand(it)
            logInfo("[UI_ACTION] Adapter Wi-Fi band: ${it.code}", tag = "UI")
        },
        subtitle = ApLinkText.bandLine(settings, adapterInfo),
        icon = Icons.Default.Router,
    )
    GroupDivider()
    val blocked = ApLinkText.blocked(live.wifiLinkSupported)
    val setUp = {
        onOutcome(null)
        scope.launch {
            val sent = carlinkManager.wifiSetup.sendAp(settings.band)
            onOutcome(ApLinkText.sentLine(sent, adapterInfo))
            logInfo("[UI_ACTION] Adapter Wi-Fi set up: $sent", tag = "UI")
        }
    }
    SettingRow(
        title = if (settings.apLink) ApLinkText.SET_UP_AGAIN_TITLE else ApLinkText.SET_UP_TITLE,
        subtitle = if (busy) ApLinkText.SETTING_UP else outcome ?: ApLinkText.readyLine(blocked, live.parked),
        icon = Icons.Default.WifiTethering,
    ) {
        RowAction(ApLinkText.SET_UP_ACTION, enabled = !busy && blocked == null && live.parked, onClick = {
            if (ApLinkText.movesBand(settings, adapterInfo)) askBand = true else setUp()
        })
    }
    if (settings.apLink) {
        val context = LocalContext.current
        JoinCard(settings, live.parked, loadPassword = { carlinkManager.wifiLink.apPassword() }, openSettings = { WifiSettingsPage.open(context) })
    }
    StateLines(ApLinkText.stateLines(settings, adapterInfo, miss, linkedOver, live.bridgeInstalled))
    // M3: with no session to set up through, the adapter's own set-up network.
    if (blocked == WifiLinkSetup.Sent.NO_SESSION) ApSetupNetworkRow(carlinkManager, settings.band, enabled = live.parked && !busy)
    if (askBand) {
        CarlinkDialog(
            title = ApLinkText.bandDialogTitle(settings.band),
            text = ApLinkText.bandDialogText(settings.band),
            confirmLabel = ApLinkText.BAND_CONFIRM,
            weight = DialogWeight.CAUTION,
            icon = Icons.Default.Router,
            onConfirm = {
                askBand = false
                setUp()
            },
            onDismiss = { askBand = false },
        )
    }
}

/** A heading and its words, as the hotspot link's fields had them. */
@Composable
private fun Intro(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = SettingsStyle.RowTitle)
        Text(body, style = SettingsStyle.RowSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What Settings shows of the adapter's Wi-Fi password, read from the Keystore when the card shows. */
private sealed interface PasswordShown {
    data object Loading : PasswordShown

    data object NotKept : PasswordShown

    class Kept(
        val text: String,
    ) : PasswordShown
}

/**
 * The adapter's own Wi-Fi to join (ADDENDUM 24 item 7): its name and password, the steps in the car's Wi-Fi settings, and
 * that the head unit has no internet over it. The manual way always works; Open Wi-Fi settings ([openSettings], false where
 * the head unit has no such page) only saves the driver finding the page. The password ([loadPassword], the Keystore's) is
 * shown parked only, and never logged.
 */
@Composable
internal fun JoinCard(
    settings: WifiLinkSettings,
    parked: Boolean,
    loadPassword: suspend () -> String?,
    openSettings: () -> Boolean,
) {
    var noSettingsPage by remember { mutableStateOf(false) }
    val password by produceState<PasswordShown>(PasswordShown.Loading, settings.apPasswordKept, settings.apName) {
        value =
            if (!settings.apPasswordKept) {
                PasswordShown.NotKept
            } else {
                withContext(Dispatchers.IO) { loadPassword() }?.let { PasswordShown.Kept(it) } ?: PasswordShown.NotKept
            }
    }
    GroupDivider()
    SettingRow(title = ApLinkText.JOIN_TITLE, subtitle = ApLinkText.NO_INTERNET, icon = Icons.Default.SignalWifiConnectedNoInternet4) {
        RowAction(ApLinkText.OPEN_WIFI_SETTINGS, enabled = parked, onClick = {
            noSettingsPage = !openSettings()
            logInfo("[UI_ACTION] Open Wi-Fi settings: ${if (noSettingsPage) "no page" else "opened"}", tag = "UI")
        })
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.padding(start = 88.dp, end = 32.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (settings.apName.isEmpty()) {
            Text(ApLinkText.NOT_NAMED, style = SettingsStyle.RowSubtitle, color = muted)
        } else {
            Detail(ApLinkText.NETWORK_LABEL, settings.apName, typed = true)
            val p = password
            val typed = parked && p is PasswordShown.Kept && p.text.isNotEmpty()
            val shown =
                when {
                    !parked -> ApLinkText.PASSWORD_WHILE_PARKED
                    p is PasswordShown.Kept -> p.text.ifEmpty { ApLinkText.PASSWORD_NONE }
                    p == PasswordShown.NotKept -> ApLinkText.PASSWORD_NOT_KEPT
                    else -> "..."
                }
            Detail(ApLinkText.PASSWORD_LABEL, shown, typed = typed)
            ApLinkText.JOIN_STEPS.forEach { Text(it, style = SettingsStyle.RowSubtitle, color = muted) }
            if (noSettingsPage) Text(ApLinkText.NO_WIFI_SETTINGS, style = SettingsStyle.RowSubtitle, color = muted)
        }
    }
}

/** One labelled value of the network to join; what the driver types in the car's settings ([typed]) in monospace. */
@Composable
private fun Detail(
    label: String,
    value: String,
    typed: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = SettingsStyle.RowSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
        Text(value, style = if (typed) SettingsStyle.RowTitle.copy(fontFamily = FontFamily.Monospace) else SettingsStyle.RowSubtitle)
    }
}

/** The car's Wi-Fi settings, where the driver joins the adapter's network. False where the head unit has no such page. */
internal object WifiSettingsPage {
    fun open(context: Context): Boolean =
        try {
            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            logWarn("[WIFI] no Wi-Fi settings page: ${e.message}", tag = "UI")
            false
        } catch (e: SecurityException) {
            logWarn("[WIFI] the Wi-Fi settings page refused Carlink: ${e.message}", tag = "UI")
            false
        }
}

/** The AP link's set-up over the adapter's set-up network: join it, set the adapter up, let it go (parked only). */
@Composable
private fun ApSetupNetworkRow(
    carlinkManager: CarlinkManager,
    band: ApBand,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    val phase by carlinkManager.setupNetwork.phase.collectAsState()
    val result by carlinkManager.setupNetwork.result.collectAsState()
    val confirm by carlinkManager.setupNetwork.confirm.collectAsState()
    val running = phase != SetupOverNetwork.Phase.IDLE && phase != SetupOverNetwork.Phase.DONE
    GroupDivider()
    SettingRow(title = WifiLinkText.SETUP_NETWORK_TITLE, subtitle = ApLinkText.setupNetworkLine(phase, result), icon = Icons.Default.WifiTethering) {
        RowAction(ApLinkText.SET_UP_ACTION, enabled = enabled && !running, onClick = {
            scope.launch {
                val r = carlinkManager.setUpApOverSetupNetwork(band)
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
    settings: WifiLinkSettings,
    bridgeInstalled: Boolean,
    onDone: () -> Unit,
    onOutcome: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    CarlinkDialog(
        title = WifiLinkText.REMOVE_DIALOG_TITLE,
        text =
            if (settings.mode == LinkMode.AP) {
                ApLinkText.removeDialogText(settings, bridgeInstalled)
            } else {
                WifiLinkText.removeDialogText(settings.ssid, bridgeInstalled)
            },
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

@Composable
internal fun StateLines(lines: List<String>) {
    if (lines.isEmpty()) return
    Column(modifier = Modifier.padding(start = 88.dp, end = 32.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { Text(it, style = SettingsStyle.RowSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
