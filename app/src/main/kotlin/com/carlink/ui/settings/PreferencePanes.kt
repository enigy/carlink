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
import androidx.compose.runtime.remember
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
 * and Reconnect restarts that binding when the cluster stops updating.
 */
@Composable
internal fun ClusterPane(
    live: SettingsLiveState,
    onResetCluster: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.widthIn(max = PANE_MAX_WIDTH), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        PaneHeader(title = "Cluster")
        SettingsGroup {
            SwitchRow(
                title = "Directions on cluster",
                subtitle = "CarPlay turn-by-turn on the driver display",
                checked = live.clusterOn,
                icon = Icons.Default.Navigation,
                onCheckedChange = { on ->
                    live.clusterOn = on
                    logWarn("[UI_ACTION] Cluster Navigation -> ${if (on) "on" else "off"}", tag = "UI")
                    ClusterNavigation.setEnabled(context, on)
                    if (on) onResetCluster()
                },
            )
            GroupDivider()
            SettingRow(
                title = "Reconnect cluster",
                subtitle = if (live.clusterOn) "Use if directions stop updating mid-route" else "Turn directions on first",
                icon = Icons.Default.Refresh,
            ) {
                RowAction(
                    label = "Reconnect",
                    enabled = live.clusterOn,
                    onClick = {
                        logWarn("[UI_ACTION] Reconnect cluster clicked", tag = "UI")
                        onResetCluster()
                    },
                )
            }
        }
        TurnAlertGroup()
        LaneTestsGroup()
    }
}
