package com.carlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.audio.EchoTest
import com.carlink.audio.VoiceTest
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Calls → Call audio tests (ported from Carlink2 [223]/[224]): which call path gives the
 * caller the least echo and the clearest voice on this truck. Both run parked, without a call, and
 * try every path in `callPathsToCompare`: Carlink's two Call microphone choices, Carlink2's
 * equivalents, and the raw mic as the baseline. Summaries show here; the details go to the log
 * ([ECHO_TEST], [VOICE_TEST]), and during real calls the [ECHO_MON] lines add the in-call view.
 */
@Composable
internal fun CallAudioTests(
    carlinkManager: CarlinkManager,
    modifier: Modifier = Modifier,
) {
    // One Column, spaced like CallsPane's, so this emits a single node (ComposeMultipleContentEmitters).
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionLabel("Call audio tests", modifier = Modifier.padding(top = 16.dp))
        Text(
            "Park, pause music and keep the cabin quiet. Turn the volume up to a normal call level first.",
            style = SettingsStyle.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsGroup {
            EchoTestRow(carlinkManager)
            GroupDivider()
            VoiceTestRow(carlinkManager)
        }
    }
}

@Composable
private fun EchoTestRow(carlinkManager: CarlinkManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf(emptyList<String>()) }
    SettingRow(
        title = "Echo test",
        subtitle = status ?: "Plays tones on the call speakers and records them on each call path. About 45 seconds.",
        icon = Icons.Default.GraphicEq,
    ) {
        RowAction(
            label = if (running) "Running…" else "Run",
            enabled = !running,
            onClick = {
                logWarn("[UI_ACTION] Echo test", tag = "UI")
                running = true
                results = emptyList()
                status = "Starting…"
                scope.launch {
                    results =
                        withContext(Dispatchers.IO) {
                            EchoTest(
                                context.applicationContext,
                                blockedReason = { carlinkManager.audioTestBlockedReason() },
                                log = { logInfo(it, tag = Logger.Tags.MIC) },
                                callBufferMultiplier = carlinkManager.callBufferMultiplier,
                            ).run { status = it }
                        }
                    status = null
                    running = false
                }
            },
        )
    }
    ResultLines(results)
}

@Composable
private fun VoiceTestRow(carlinkManager: CarlinkManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<VoiceTest.Outcome?>(null) }
    val test =
        remember {
            VoiceTest(
                context.applicationContext,
                blockedReason = { carlinkManager.audioTestBlockedReason() },
                log = { logInfo(it, tag = Logger.Tags.MIC) },
            )
        }
    SettingRow(
        title = "Voice test",
        subtitle = status ?: "Count slowly to five each time it says \"Talk now\", then hear each path played back. About 90 seconds.",
        icon = Icons.Default.Mic,
        tint = if (status?.startsWith("Talk now") == true) MaterialTheme.colorScheme.primary else Color.Unspecified,
    ) {
        RowAction(
            label = if (running) "Running…" else "Run",
            enabled = !running,
            onClick = {
                logWarn("[UI_ACTION] Voice test", tag = "UI")
                running = true
                outcome = null
                status = "Starting…"
                scope.launch {
                    outcome = withContext(Dispatchers.IO) { test.run { status = it } }
                    status = null
                    running = false
                }
            },
        )
    }
    outcome?.let { o ->
        TakeRows(o.takes, playEnabled = !running) { take ->
            running = true
            status = "Playing back: ${take.label}"
            scope.launch {
                status = withContext(Dispatchers.IO) { test.play(take) }
                running = false
            }
        }
        ResultLines(o.notes)
    }
}

/** One row per recorded path, with its levels and a Play button to hear it again. */
@Composable
private fun TakeRows(
    takes: List<VoiceTest.Take>,
    playEnabled: Boolean,
    onPlay: (VoiceTest.Take) -> Unit,
) {
    takes.forEach { take ->
        GroupDivider()
        SettingRow(
            title = take.label,
            subtitle =
                take.error?.let { "Failed: $it" }
                    ?: "Voice ${"%.0f".format(take.voiceDbfs)} dBFS, ${"%.0f".format(take.rangeDb)} dB above background",
        ) {
            if (take.pcm.isNotEmpty()) RowAction(label = "Play", enabled = playEnabled, onClick = { onPlay(take) })
        }
    }
}

/** A test's summary lines, under its row. */
@Composable
private fun ResultLines(lines: List<String>) {
    if (lines.isEmpty()) return
    GroupDivider()
    Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 20.dp)) {
        lines.forEach { Text(it, style = SettingsStyle.RowSubtitle, modifier = Modifier.padding(vertical = 4.dp)) }
    }
}
