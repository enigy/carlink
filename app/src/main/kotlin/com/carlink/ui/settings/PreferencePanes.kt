package com.carlink.ui.settings

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.audio.CallPaths
import com.carlink.cluster.ClusterNavigation
import com.carlink.logging.logWarn

/** Panes hold their controls to a readable width on a very wide head unit. */
private val PANE_MAX_WIDTH = 1100.dp

/**
 * Calls: which input a phone call records from and the call track's buffer ([226]), both applied
 * the moment they are tapped. Call microphone offers Automatic, the system's choice, and every
 * input this car lists that a call could use ([CallPaths.candidates]); the line under it says what
 * Automatic picked and why.
 */
@Composable
internal fun CallsPane(
    carlinkManager: CarlinkManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val callAudio = remember { CallAudioStore.get(context) }
    val callMic by callAudio.callMicFlow.collectAsState()
    val callBuffer by callAudio.callBufferFlow.collectAsState()
    val autoPath by callAudio.autoPathFlow.collectAsState()
    val inputKeys =
        remember {
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let { am -> CallPaths.candidates(am).map { CallPaths.key(it) } }.orEmpty()
        }
    val hint = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.widthIn(max = PANE_MAX_WIDTH), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PaneHeader(title = "Calls")
        SectionLabel("Call microphone")
        ChoiceRow(
            options = CallMicChoices.options(inputKeys, callMic),
            selected = callMic,
            onSelect = {
                logWarn("[UI_ACTION] Call Mic -> ${it.code}", tag = "UI")
                callAudio.setCallMic(it)
            },
        )
        Text(
            CallMicChoices.note(callMic, autoPath, android.os.Build.FINGERPRINT, inputKeys.isNotEmpty()),
            style = SettingsStyle.Body,
            color = hint,
        )
        SectionLabel("Call audio buffer", modifier = Modifier.padding(top = 16.dp))
        ChoiceRow(
            options = CallBufferConfig.entries,
            selected = callBuffer,
            onSelect = {
                logWarn("[UI_ACTION] Call Buffer -> ${it.name}", tag = "UI")
                callAudio.setCallBuffer(it)
            },
        )
        Text("Standard rides out network hiccups. Low cuts delay. Applies on the next call.", style = SettingsStyle.Body, color = hint)
        CallAudioTests(carlinkManager)
    }
}

/**
 * Cluster: CarPlay turn-by-turn on the driver display through the Templates Host. On by default;
 * turning it on enables the CarAppService component and starts the binding at once ([onResetCluster]),
 * and Reconnect restarts that binding when the cluster stops updating. The rows below the profile bar
 * are the shown iPhone's ([DriverProfiles.View]); another iPhone's are only stored until it connects.
 */
@Composable
internal fun ClusterPane(
    live: SettingsLiveState,
    onResetCluster: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view by remember { DriverProfiles.view(context) }.collectAsState()
    val other = view.other
    // Another iPhone's choice is read from its file; the one in use follows live (polled).
    var otherOn by remember(other, view.revision) { mutableStateOf(other?.let { ClusterNavigation.isEnabled(context, it.mac) } ?: true) }
    val directions = if (other != null) otherOn else live.clusterOn
    Column(modifier = modifier.widthIn(max = PANE_MAX_WIDTH), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        PaneHeader(title = "Cluster")
        ProfileBar(
            view = view,
            connectedMac = live.phone.btMac,
            driving = live.driving,
            onRewritten = {
                // A copy or reset of the profile in use may have turned directions on: bind now, as the switch does.
                if (view.other == null) {
                    val on = ClusterNavigation.isEnabled(context)
                    if (on && !live.clusterOn) onResetCluster()
                    live.clusterOn = on
                }
            },
        )
        SettingsGroup {
            SwitchRow(
                title = "Directions on cluster",
                subtitle = "CarPlay turn-by-turn on the driver display",
                checked = directions,
                icon = Icons.Default.Navigation,
                onCheckedChange = { on ->
                    logWarn("[UI_ACTION] Cluster Navigation -> ${if (on) "on" else "off"}${other?.let { " for ${it.mac}" }.orEmpty()}", tag = "UI")
                    if (other != null) {
                        otherOn = on
                        ClusterNavigation.setEnabled(context, on, other.mac)
                    } else {
                        live.clusterOn = on
                        ClusterNavigation.setEnabled(context, on)
                        if (on) onResetCluster()
                    }
                },
            )
            if (other == null) {
                GroupDivider()
                ReconnectRow(live.clusterOn, onResetCluster)
            }
        }
        TurnAlertGroup()
        LaneGuidanceGroup()
    }
}

/** Restarts the cluster binding of the iPhone in use; under its Directions switch. */
@Composable
private fun ReconnectRow(
    clusterOn: Boolean,
    onResetCluster: () -> Unit,
) {
    SettingRow(
        title = "Reconnect cluster",
        subtitle = if (clusterOn) "Use if directions stop updating mid-route" else "Turn directions on first",
        icon = Icons.Default.Refresh,
    ) {
        RowAction(
            label = "Reconnect",
            enabled = clusterOn,
            onClick = {
                logWarn("[UI_ACTION] Reconnect cluster clicked", tag = "UI")
                onResetCluster()
            },
        )
    }
}
