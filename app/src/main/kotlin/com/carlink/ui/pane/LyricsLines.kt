package com.carlink.ui.pane

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.carlink.car.DrivingState
import com.carlink.logging.logInfo
import com.carlink.media.LrcLib
import com.carlink.media.LyricsDiskCache
import com.carlink.media.LyricsKey
import com.carlink.media.NowPlayingInfo
import com.carlink.media.NowPlayingState
import com.carlink.media.SyncedLyrics
import com.carlink.ui.components.withGlowRoom
import com.carlink.ui.settings.CarPlayLimitStore
import com.carlink.ui.settings.MusicStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The playing song's timed lyrics ([LrcLib]), or null while none are known, the card's lyrics button
 * ([LyricsToggle]) is off, or they are held back while the vehicle moves ([rememberLyricsHeldBack]).
 * Looked up either way, from the head unit's own copies first ([LyricsDiskCache]), and asked again until
 * LRCLIB answers. A song looked up before shows at once; a new one waits [LOOKUP_SETTLE_MS], because a
 * skip's title, length and album land over about a second (log 10-26) and the lookup should ask for the
 * settled song once. Turning the lyrics on asks again at once, a "none" too: before, turning them off and
 * on was the only way to get lyrics LRCLIB had failed to give (owner, 10-29). Call this where it stays
 * composed (the pane's host, as for the album art), so the words are ready when the driver swipes to the music.
 * With Settings' Look up lyrics off ([MusicStore.lookUpFlow]) nothing is looked up, anywhere, and none show.
 */
@Composable
internal fun rememberLyrics(info: NowPlayingInfo): SyncedLyrics? {
    val context = LocalContext.current
    val music = remember { MusicStore.get(context) }
    val on by music.lyricsFlow.collectAsState()
    val lookUp by music.lookUpFlow.collectAsState()
    val heldBack = rememberLyricsHeldBack()
    val disk = remember { LyricsDiskCache.get(context) }
    val key = LyricsKey.of(info)
    var lyrics by remember { mutableStateOf<SyncedLyrics?>(null) }
    // Looked up whether the lyrics show or not, so turning them on finds them ready (owner, 10-28); not at all
    // with Look up lyrics off (owner, 10-30).
    LaunchedEffect(key, on, lookUp) {
        lyrics = null
        if (key == null || !lookUp) return@LaunchedEffect
        LrcLib.cached(key)?.takeIf { it.lyrics != null || !on }?.let {
            lyrics = it.lyrics
            return@LaunchedEffect
        }
        delay(LOOKUP_SETTLE_MS)
        // LRCLIB out of reach (the truck's data link at connect, a timeout, its HTTP 503s) is tried again
        // while the song plays, waiting longer each time up to [RETRY_MAX_MS] (owner, 10-28 and 10-29).
        var wait = RETRY_FIRST_MS
        while (true) {
            val answer = withContext(Dispatchers.IO) { LrcLib.find(key, disk, askAgain = on) }
            if (answer != null) {
                lyrics = answer.lyrics
                break
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(RETRY_MAX_MS)
        }
    }
    return if (on && lookUp && !heldBack) lyrics else null
}

/**
 * Whether the lyrics are held back now: Settings › Display › Limit features while driving is on (the
 * active iPhone's choice, [CarPlayLimitStore]) and the vehicle moves ([DrivingState]). Parked, they show
 * (owner, 10-29).
 */
@Composable
internal fun rememberLyricsHeldBack(): Boolean {
    val context = LocalContext.current
    val limit by remember { CarPlayLimitStore.get(context) }.flow.collectAsState()
    val moving by DrivingState.moving.collectAsState()
    return limit && moving
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
 * MOVING ON (prototype C, "Drift"): at the next line the new sung line grows into its size as the old
 * one shrinks out of it, a line opens in at the bottom, and the line falling off the top slides up and
 * dissolves into mist the way the loading screen's words do (EtherealStatus: it rises, spreads and blurs
 * away). Every line is laid out at the sung size and drawn smaller when it is not sung, so a line that
 * wraps onto two when sung wraps the same way before and after: nothing reflows as it takes or leaves
 * the sung place (owner, 10-28), and each line's space eases with it ([LyricsDrift.kt]). A jump (a seek,
 * a new song) opens the new lines in.
 *
 * HOW MANY (prototype C, "Drag the edge"): a vertical drag on the lyrics sets the count, up for more
 * and down for fewer, a line per [LINE_STEP]; the small handle on the frost's top edge says it can be
 * dragged. Lines open in and close out as the count changes. The block is as tall as its lines, so the
 * title under it moves with them, smoothly; the pane's frost, which starts above the block, follows.
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
    val drift = rememberDrift(lyrics, index, behind, ahead)
    val sungSize = MaterialTheme.typography.titleLarge.scaled(WORD_SCALE)
    val sung = sungSize.copy(fontWeight = FontWeight.SemiBold)
    // The other lines are the sung style drawn at the size the next-line style had.
    val nearScale = MaterialTheme.typography.titleMedium.fontSize.value / MaterialTheme.typography.titleLarge.fontSize.value
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .lineCountDrag(music)
                .semantics { contentDescription = "Lyrics, $count lines: drag up for more, down for fewer" },
    ) {
        val handle = LocalContentColor.current.copy(alpha = HANDLE_ALPHA)
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = HANDLE_TOP)
                    .size(HANDLE_WIDTH, HANDLE_HEIGHT)
                    .background(handle, CircleShape),
        )
        // One element per line, keyed by its place in the song, the lines still dissolving among them in
        // song order: a line keeps its identity while it changes role, so its own motion follows it.
        Column(modifier = Modifier.padding(top = HANDLE_ROOM)) {
            val lines = (drift.shown + drift.leaving.keys).toSortedSet()
            for (i in lines) {
                key(i) { LyricRow(lyrics.lines.getOrNull(i)?.text, k = i - drift.at, leaving = i in drift.leaving, style = sung, nearScale = nearScale) }
            }
        }
    }
}

/**
 * One lyric line [k] places from the sung one: a note for a gap, nothing (but its space) past either end
 * of the song. Laid out in the sung [style] whatever its role, so it wraps as it would when sung, and
 * drawn at [nearScale] when it is not; its size, dimness, space and, when [leaving], its mist all ease
 * rather than snap ([LyricsDrift.kt]).
 */
@Composable
private fun LyricRow(
    text: String?,
    k: Int,
    leaving: Boolean,
    style: TextStyle,
    nearScale: Float,
) {
    val isSung = k == 0 && !leaving
    val dim = animateFloatAsState(if (isSung) 1f else nearAlpha(k), tween(GLIDE_MS), label = "lyric dim")
    val scale = animateFloatAsState(if (isSung) 1f else nearScale, tween(GLIDE_MS, easing = FastOutSlowInEasing), label = "lyric size")
    val open = rememberOpen(leaving)
    val mist = rememberMist(leaving)
    val density = LocalDensity.current
    val room = with(density) { GLOW_ROOM.toPx() }
    val gap = with(density) { LINE_GAP.roundToPx() }
    Text(
        text?.ifBlank { NOTE }.orEmpty(),
        style = style,
        color = LocalContentColor.current,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .lyricBox(scale = { scale.value }, open = { open.value }, gapPx = gap, rise = leaving && k < 0)
                .withGlowRoom(GLOW_ROOM)
                .graphicsLayer { lyricMotion(dim.value, scale.value, open.value, mist.value, room) }
                .padding(GLOW_ROOM),
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
 * dim icon, and the words are hidden; they are still looked up, so turning them on shows them at once.
 * The choice is kept across drives ([MusicStore]).
 *
 * While the lyrics are held back ([rememberLyricsHeldBack]: the vehicle moves with Limit features while
 * driving on) the button stays, dimmed, and a tap shows a bubble saying why instead of switching
 * (owner, 10-29). With Settings' Look up lyrics off there is no button at all.
 */
@Composable
internal fun LyricsToggle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val music = remember { MusicStore.get(context) }
    val lookUp by music.lookUpFlow.collectAsState()
    if (!lookUp) return
    val on by music.lyricsFlow.collectAsState()
    val heldBack = rememberLyricsHeldBack()
    var bubble by remember { mutableStateOf(false) }
    val content = LocalContentColor.current
    val lit = on && !heldBack
    Box(modifier = modifier) {
        PaneButton(
            Icons.Default.Lyrics,
            when {
                heldBack -> "Lyrics are off while driving"
                on -> "Hide lyrics"
                else -> "Show lyrics"
            },
            onClick = {
                if (heldBack) {
                    logInfo("[UI_ACTION] Pane: lyrics tapped while driving (Limit features while driving)", tag = "UI")
                    bubble = true
                } else {
                    logInfo("[UI_ACTION] Pane: lyrics ${if (on) "off" else "on"}", tag = "UI")
                    music.setLyrics(!on)
                }
            },
            size = 56.dp,
            iconSize = 30.dp,
            fill = if (lit) content.copy(alpha = LIT_ALPHA) else Color.Transparent,
            tint = content.copy(alpha = if (lit) 1f else OFF_ALPHA),
        )
        if (bubble && heldBack) LimitBubble(onDismiss = { bubble = false })
    }
}

/** "Lyrics are off while driving", under the lyrics button, until a tap elsewhere or [BUBBLE_MS]. */
@Composable
private fun LimitBubble(onDismiss: () -> Unit) {
    val below = with(LocalDensity.current) { BUBBLE_DROP.roundToPx() }
    LaunchedEffect(Unit) {
        delay(BUBBLE_MS)
        onDismiss()
    }
    Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, below), onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
                Text("Lyrics are off while driving", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Limit features while driving is on. Lyrics show again when the vehicle stops, or turn the limit off in Settings › Display.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private const val NOTE = "♪"
private const val LIT_ALPHA = 0.22f
private const val OFF_ALPHA = 0.45f
private const val LOOKUP_SETTLE_MS = 1_500L

/** The first wait before LRCLIB is asked again after it could not be reached, doubling up to [RETRY_MAX_MS]. */
private const val RETRY_FIRST_MS = 5_000L

/** At most this between tries: a song is three or four minutes, and a minute's wait lost most of one (10-29). */
private const val RETRY_MAX_MS = 30_000L

/** How long the "off while driving" bubble stays, and how far under the button's top it hangs. */
private const val BUBBLE_MS = 5_000L
private val BUBBLE_DROP = 64.dp

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
