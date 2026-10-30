package com.carlink.ui.settings

import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.media.LrcLib
import com.carlink.media.LyricsDiskCache
import com.carlink.ui.components.LocalSettingsVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Adapter and about › Lyrics (owner, 10-30): Look up lyrics, the switch that keeps every song
 * name on the head unit when off ([MusicStore.lookUpFlow]; the privacy policy points to it); where the
 * music card's lyrics come from (LRCLIB); how many songs' lyrics the head unit keeps ([LyricsDiskCache])
 * and the space they take; and Clear, which deletes them all, so each is looked up again the next time
 * it plays. Read each time Settings opens.
 */
@Composable
internal fun LyricsGroup() {
    val context = LocalContext.current
    val disk = remember { LyricsDiskCache.get(context) }
    val music = remember { MusicStore.get(context) }
    val lookUp by music.lookUpFlow.collectAsState()
    val visible = LocalSettingsVisible.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<LyricsDiskCache.Usage?>(null) }
    var cleared by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible, cleared) {
        if (visible) usage = withContext(Dispatchers.IO) { disk.usage() }
    }
    SettingsGroup {
        SwitchRow(
            title = "Look up lyrics",
            subtitle =
                if (lookUp) {
                    "Each song's title, artist, album and length go to LRCLIB, even while lyrics are hidden, so they're ready."
                } else {
                    "Off: no song is looked up and nothing leaves the head unit. The music card has no lyrics."
                },
            checked = lookUp,
            icon = Icons.Default.Cloud,
            onCheckedChange = {
                logWarn("[UI_ACTION] Look up lyrics -> $it", tag = "UI")
                music.setLookUp(it)
            },
        )
        GroupDivider()
        SettingRow(
            title = "Lyrics source",
            subtitle = "Timed lyrics, looked up by song title, artist and length. Free and crowd-sourced.",
            icon = Icons.Default.Lyrics,
        ) {
            Text("LRCLIB (lrclib.net)", style = SettingsStyle.RowTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GroupDivider()
        val kept = usage
        val size = kept?.let { it.songs to Formatter.formatShortFileSize(context, it.bytes) }
        SettingRow(title = "Lyrics on this head unit", subtitle = usageLine(size), icon = Icons.Default.Storage) {
            RowAction(
                "Clear",
                onClick = {
                    scope.launch {
                        val songs =
                            withContext(Dispatchers.IO) {
                                LrcLib.forget()
                                disk.clear()
                            }
                        logInfo("[UI_ACTION] Lyrics cache cleared: $songs songs", tag = "UI")
                        cleared++
                    }
                },
                enabled = (kept?.songs ?: 0) > 0,
            )
        }
    }
}

/** "12 songs, 48 kB", then how long they are kept; [kept] is the count and the size as shown, null while being read. */
internal fun usageLine(kept: Pair<Int, String>?): String =
    when {
        kept == null -> "Reading…"
        kept.first == 0 -> "None kept yet. $KEPT"
        else -> "${kept.first} ${if (kept.first == 1) "song" else "songs"}, ${kept.second}. $KEPT"
    }

private const val KEPT = "Each is kept 30 days, 500 MB in all at most."
