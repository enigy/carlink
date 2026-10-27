package com.carlink.ui.pane

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlink.logging.logInfo
import com.carlink.media.LrcLib
import com.carlink.media.LyricsKey
import com.carlink.media.NowPlayingInfo
import com.carlink.media.NowPlayingState
import com.carlink.media.SyncedLyrics
import com.carlink.ui.components.withGlowRoom
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

/** How a count of lyric lines ([MusicStore.lyricLinesFlow]) splits around the sung one: the odd one out goes ahead. */
internal object LyricWindow {
    fun behind(count: Int): Int = (count.coerceIn(MusicStore.MIN_LYRIC_LINES, MusicStore.MAX_LYRIC_LINES) - 1) / 2

    fun ahead(count: Int): Int = count.coerceIn(MusicStore.MIN_LYRIC_LINES, MusicStore.MAX_LYRIC_LINES) - 1 - behind(count)
}

/**
 * The song's words around the line being sung (owner, 10-27): the sung line in the title's weight,
 * the lines before and after it dimmer the farther they are, as many as the driver set ([LyricWindow],
 * three by default, up to two before and two after). A gap in the words shows a note.
 *
 * MOVING ON (prototype C, "Drift"): at the next line the lines glide up a slot, the new sung line
 * grows into its size, a line fades in at the bottom, and the line falling off the top dissolves into
 * mist the way the loading screen's words do (EtherealStatus: it rises, spreads and blurs away). A
 * jump (a seek, a new song) moves without the glide.
 *
 * HOW MANY (prototype C, "Drag the edge"): a vertical drag on the lyrics sets the count, up for more
 * and down for fewer, a line per [LINE_STEP]; the small handle on the frost's top edge says it can be
 * dragged. The block keeps the height of its lines whatever they hold, so the title under it moves
 * only when the count changes, and the pane's frost, which starts above the block, grows with it.
 */
@Composable
internal fun LyricsLines(
    lyrics: SyncedLyrics,
    state: NowPlayingState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val music = remember { MusicStore.get(context) }
    val count by music.lyricLinesFlow.collectAsState()
    val behind = LyricWindow.behind(count)
    val ahead = LyricWindow.ahead(count)
    val clock = rememberPlaybackClock(state)
    val index by remember(lyrics, state) { derivedStateOf { lyrics.indexAt(state.positionAt(clock.longValue)) } }
    val drift = rememberDrift(lyrics, index)
    val sungSize = MaterialTheme.typography.titleLarge.scaled(WORD_SCALE)
    val sung = sungSize.copy(fontWeight = FontWeight.SemiBold)
    val near = MaterialTheme.typography.titleMedium.scaled(WORD_SCALE)
    var leavingPx by remember { mutableIntStateOf(0) }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .lineCountDrag(music)
                .semantics { contentDescription = "Lyrics, $count lines: drag up for more, down for fewer" },
    ) {
        // The block's height: its lines, the sung one room for two, under the handle.
        Column(modifier = Modifier.alpha(0f).padding(top = HANDLE_ROOM)) {
            repeat(behind) { Text("", style = near, modifier = Modifier.padding(bottom = LINE_GAP)) }
            Text("", style = sung, minLines = 2, modifier = Modifier.padding(bottom = LINE_GAP))
            repeat(ahead) { Text("", style = near, modifier = Modifier.padding(bottom = LINE_GAP)) }
        }
        val handle = LocalContentColor.current.copy(alpha = HANDLE_ALPHA)
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = HANDLE_TOP)
                    .size(HANDLE_WIDTH, HANDLE_HEIGHT)
                    .background(handle, CircleShape),
        )
        Column(modifier = Modifier.align(Alignment.BottomStart).graphicsLayer { translationY = (1f - drift.glide) * leavingPx }) {
            if (drift.stepping) {
                // With no line above the sung one, the line that leaves is the one just sung.
                val look = if (behind == 0) LineLook(sung, 1f, 2) else LineLook(near, nearAlpha(-behind - 1))
                val gone = lyrics.lines.getOrNull(drift.at - behind - 1)?.text
                LyricText(gone, look, Modifier.above().onSizeChanged { leavingPx = it.height }) { mist(drift.mist) }
            }
            for (k in -behind..ahead) {
                val text = lyrics.lines.getOrNull(drift.at + k)?.text
                when {
                    k == 0 -> LyricText(text, LineLook(sung, 1f, 2)) { if (drift.stepping) lift(drift.glide) }
                    k == ahead && drift.stepping -> LyricText(text, LineLook(near, nearAlpha(k))) { emerge(drift.fade) }
                    else -> LyricText(text, LineLook(near, nearAlpha(k)))
                }
            }
        }
    }
}

/** A lyric line's type, dimness and how many lines it may wrap to: the sung one two, the rest one. */
private class LineLook(
    val style: TextStyle,
    val alpha: Float,
    val maxLines: Int = 1,
)

/** One lyric line, a note for a gap, nothing (but its room) past either end of the song; [effect] animates it. */
@Composable
private fun LyricText(
    text: String?,
    look: LineLook,
    modifier: Modifier = Modifier,
    effect: GraphicsLayerScope.() -> Unit = {},
) {
    Text(
        text?.ifBlank { NOTE }.orEmpty(),
        style = look.style,
        color = LocalContentColor.current.copy(alpha = look.alpha),
        maxLines = look.maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .withGlowRoom(GLOW_ROOM)
                .graphicsLayer(effect)
                .padding(GLOW_ROOM)
                .padding(bottom = LINE_GAP),
    )
}

/** The dimness of the line [k] places from the sung one: before it fainter than after it, both fading with distance. */
private fun nearAlpha(k: Int): Float =
    when {
        k <= -2 -> FAR_BEHIND_ALPHA
        k == -1 -> BEHIND_ALPHA
        k == 1 -> AHEAD_ALPHA
        else -> FAR_AHEAD_ALPHA
    }

/** A vertical drag sets the line count: up for more, down for fewer, one line per [LINE_STEP]. */
@Composable
private fun Modifier.lineCountDrag(music: MusicStore): Modifier {
    val stepPx = with(LocalDensity.current) { LINE_STEP.toPx() }
    var travel by remember { mutableFloatStateOf(0f) }
    val state =
        rememberDraggableState { delta ->
            travel -= delta
            val steps = (travel / stepPx).toInt()
            if (steps != 0) {
                travel -= steps * stepPx
                val was = music.lyricLinesFlow.value
                music.setLyricLines(was + steps)
                val now = music.lyricLinesFlow.value
                if (now != was) logInfo("[UI_ACTION] Pane: lyrics $was -> $now lines", tag = "UI")
            }
        }
    return draggable(state, Orientation.Vertical, onDragStopped = { travel = 0f })
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

// Room around a line for its blur: a layer draws offscreen at its own bounds and would cut it off.
private val GLOW_ROOM = 16.dp
private val LINE_GAP = 4.dp

private const val BEHIND_ALPHA = 0.45f
private const val FAR_BEHIND_ALPHA = 0.3f
private const val AHEAD_ALPHA = 0.6f
private const val FAR_AHEAD_ALPHA = 0.4f

// The drag: a line per step, and the handle above the lines that shows it.
private val LINE_STEP = 36.dp
private val HANDLE_ROOM = 20.dp
private val HANDLE_TOP = 4.dp
private val HANDLE_WIDTH = 40.dp
private val HANDLE_HEIGHT = 5.dp
private const val HANDLE_ALPHA = 0.45f
