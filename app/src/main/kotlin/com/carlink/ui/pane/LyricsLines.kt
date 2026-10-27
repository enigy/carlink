package com.carlink.ui.pane

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
 * MOVING ON (prototype C, "Drift"): at the next line every line glides up to its new place, the new
 * sung line grows into its size as the old one shrinks out of it, a line condenses in at the bottom,
 * and the line falling off the top dissolves into mist the way the loading screen's words do
 * (EtherealStatus: it rises, spreads and blurs away). Each line moves on its own ([LyricsDrift.kt]),
 * so a long line wrapping onto two, or a short one after it, glides the lines below it instead of
 * jolting the block (owner, 10-28). A jump (a seek, a new song) brings the new lines in the same way.
 *
 * HOW MANY (prototype C, "Drag the edge"): a vertical drag on the lyrics sets the count, up for more
 * and down for fewer, a line per [LINE_STEP]; the small handle on the frost's top edge says it can be
 * dragged. The block keeps the height of its lines whatever they hold (the lines sit at its top), so
 * the title under it moves only when the count changes, and then glides; the pane's frost, which
 * starts above the block, grows with it.
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
    val window = rememberDrift(lyrics, index, behind)
    val sungSize = MaterialTheme.typography.titleLarge.scaled(WORD_SCALE)
    val sung = sungSize.copy(fontWeight = FontWeight.SemiBold)
    val near = MaterialTheme.typography.titleMedium.scaled(WORD_SCALE)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .animateContentSize(tween(GLIDE_MS))
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
        // One element per line, keyed by its place in the song, from the leaving one (if any) down: a
        // line keeps its identity while it moves, so its own glide, size and dimness can follow it.
        Column(modifier = Modifier.padding(top = HANDLE_ROOM)) {
            val leaving = window.leaving?.takeIf { it < window.at - behind }
            for (i in (leaving ?: (window.at - behind))..(window.at + ahead)) {
                key(i) { LyricRow(lyrics.lines.getOrNull(i)?.text, k = i - window.at, leaving = i == leaving, sung = sung, near = near) }
            }
        }
    }
}

/**
 * One lyric line [k] places from the sung one: a note for a gap, nothing (but its room) past either end
 * of the song. Its dimness, size, place, entrance and, when [leaving], its mist all ease rather than
 * snap ([LyricsDrift.kt]).
 */
@Composable
private fun LyricRow(
    text: String?,
    k: Int,
    leaving: Boolean,
    sung: TextStyle,
    near: TextStyle,
) {
    val isSung = k == 0 && !leaving
    val dim = animateFloatAsState(if (isSung) 1f else nearAlpha(k), tween(GLIDE_MS), label = "lyric dim")
    val role = rememberRoleScale(isSung, ratio = sung.fontSize.value / near.fontSize.value)
    val enter = rememberEntrance()
    val mist = rememberMist(leaving)
    val place = if (leaving) Modifier.above() else Modifier
    Text(
        text?.ifBlank { NOTE }.orEmpty(),
        style = if (isSung) sung else near,
        color = LocalContentColor.current,
        maxLines = if (isSung) 2 else 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            place
                .glidePlacement()
                .withGlowRoom(GLOW_ROOM)
                .graphicsLayer { lyricMotion(dim.value, role.value, enter.value, mist.value) }
                .padding(GLOW_ROOM)
                .padding(bottom = LINE_GAP),
    )
}

/**
 * The lyrics gliding in and out over the title (owner, 10-28: "glide in/out"): turning them on, or a
 * song with lyrics arriving, unfolds the block up from the title as its words fade in; turning them
 * off, or a song without, folds it back down into the title. The last lyrics stay in view while they
 * fold away. Spaced from the title by [LYRICS_TO_TITLE], which folds with them.
 */
@Composable
internal fun LyricsReveal(
    lyrics: SyncedLyrics?,
    state: NowPlayingState,
    modifier: Modifier = Modifier,
) {
    val kept = remember { arrayOfNulls<SyncedLyrics>(1) }
    if (lyrics != null) kept[0] = lyrics
    val shown = lyrics ?: kept[0]
    AnimatedVisibility(
        visible = lyrics != null,
        modifier = modifier,
        enter = expandVertically(tween(GLIDE_MS), expandFrom = Alignment.Bottom) + fadeIn(tween(GLIDE_MS)),
        exit = shrinkVertically(tween(GLIDE_MS), shrinkTowards = Alignment.Bottom) + fadeOut(tween(GLIDE_MS)),
        label = "lyrics",
    ) {
        if (shown != null) LyricsLines(shown, state, modifier = Modifier.padding(bottom = LYRICS_TO_TITLE))
    }
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

/** Between the lyrics and the title under them; folds away with the lyrics ([LyricsReveal]). */
private val LYRICS_TO_TITLE = 24.dp

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
