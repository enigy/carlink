package com.carlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.logging.logWarn

/** The CarPlay app URLs to try (Apple's `requestUI` schemes; which ones this box and iOS honour is the question). */
internal val CARPLAY_URL_BENCH = listOf("maps:", "nowplaying:", "mobilephone:", "music:")

/**
 * Debug builds only: one button per candidate URL, each a `requestUI` with that URL to the main
 * screen, so the box change (CMD_REQUEST_UI_URL, box commit cbdd60b) can be benched with a phone
 * connected. The log line is `[CARPLAY_UI] requestUI url=…`; the box answers `[input] requestUI
 * url=… sent=…`. Remove once the working URLs are known.
 */
@Composable
internal fun CarPlayUrlBench(carlinkManager: CarlinkManager) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("CarPlay URL test (debug build)", modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CARPLAY_URL_BENCH.forEach { url ->
                OutlinedButton(onClick = {
                    logWarn("[UI_ACTION] CarPlay URL bench -> $url", tag = "UI")
                    carlinkManager.openCarPlayApp(url)
                }) { Text(url, style = SettingsStyle.Action) }
            }
        }
        Text(
            "Each button asks iOS to bring that app forward on the main screen. Note which ones work.",
            style = SettingsStyle.RowSubtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
