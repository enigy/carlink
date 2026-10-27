package com.carlink.ui.pane

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlink.logging.logInfo
import com.carlink.media.LrcLib
import com.carlink.media.LyricsKey
import com.carlink.media.NowPlayingInfo
import com.carlink.media.NowPlayingState
import com.carlink.media.SyncedLyrics
import com.carlink.ui.settings.MusicStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The playing song's timed lyrics ([LrcLib]), or null while none are known or the card's lyrics
 * button ([LyricsToggle]) is off. A song looked up before shows at once; a new one waits
 * [LOOKUP_SETTLE_MS], because a skip's title, length and album land over about a second (log 10-26)
 * and the lookup should ask for the settled song once. Call this where it stays composed (the pane's
 * host, as for the album art), so the words are ready when the driver swipes to the music.
 */
@Composable
internal fun rememberLyrics(info: NowPlayingInfo): SyncedLyrics? {
    val context = LocalContext.current
    val on by remember { MusicStore.get(context) }.lyricsFlow.collectAsState()
    val key = if (on) LyricsKey.of(info) else null
    var lyrics by remember { mutableStateOf<SyncedLyrics?>(null) }
    LaunchedEffect(key) {
        lyrics = null
        if (key == null) return@LaunchedEffect
        val known = LrcLib.cached(key)
        if (known != null) {
            lyrics = known.lyrics
            return@LaunchedEffect
        }
        delay(LOOKUP_SETTLE_MS)
        lyrics = withContext(Dispatchers.IO) { LrcLib.find(key) }
    }
    return lyrics
}

/**
 * The line being sung, in the title's weight, and the next one dimmed, crossfading as the song moves
 * on; a gap in the words shows a note. Before the first line only the first line shows, dimmed. The
 * block keeps the height of two sung lines and one next line whatever it shows, so the title under it
 * never moves.
 */
@Composable
internal fun LyricsLines(
    lyrics: SyncedLyrics,
    state: NowPlayingState,
    modifier: Modifier = Modifier,
) {
    val clock = rememberPlaybackClock(state)
    val index by remember(lyrics, state) { derivedStateOf { lyrics.indexAt(state.positionAt(clock.longValue)) } }
    val sungSize = MaterialTheme.typography.titleLarge.scaled(WORD_SCALE)
    val sung = sungSize.copy(fontWeight = FontWeight.SemiBold)
    val next = MaterialTheme.typography.titleMedium.scaled(WORD_SCALE)
    val dim = LocalContentColor.current.copy(alpha = NEXT_ALPHA)
    Box(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.alpha(0f)) {
            Text("", style = sung, minLines = 2)
            Text("", style = next, minLines = 1)
        }
        Crossfade(targetState = index, animationSpec = tween(LINE_FADE_MS), label = "lyrics", modifier = Modifier.align(Alignment.BottomStart)) { i ->
            Column {
                lyrics.lines.getOrNull(i)?.let { Text(it.text.ifBlank { NOTE }, style = sung, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                lyrics.lines.getOrNull(i + 1)?.let { Text(it.text.ifBlank { NOTE }, style = next, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * The music card's lyrics switch, in the pane's header beside the full-screen button (owner, 10-27: on
 * the pane itself, not in Settings). On: a lit disc, and the song's words show over the title. Off: a
 * dim icon, and nothing goes online. The choice is kept across drives ([MusicStore]).
 */
@Composable
internal fun LyricsToggle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val music = remember { MusicStore.get(context) }
    val on by music.lyricsFlow.collectAsState()
    val content = LocalContentColor.current
    PaneButton(
        Icons.Default.Lyrics,
        if (on) "Hide lyrics" else "Show lyrics",
        onClick = {
            logInfo("[UI_ACTION] Pane: lyrics ${if (on) "off" else "on"}", tag = "UI")
            music.setLyrics(!on)
        },
        modifier = modifier,
        size = 56.dp,
        iconSize = 30.dp,
        fill = if (on) content.copy(alpha = LIT_ALPHA) else Color.Transparent,
        tint = content.copy(alpha = if (on) 1f else OFF_ALPHA),
    )
}

private const val NOTE = "♪"
private const val LIT_ALPHA = 0.22f
private const val OFF_ALPHA = 0.45f
private const val LOOKUP_SETTLE_MS = 1_500L
private const val LINE_FADE_MS = 250
private const val NEXT_ALPHA = 0.6f
