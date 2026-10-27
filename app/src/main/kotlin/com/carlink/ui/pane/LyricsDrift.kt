package com.carlink.ui.pane

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.carlink.media.SyncedLyrics
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// The music card's lyric motion ([LyricsLines]; owner 10-27 "Drift", 10-28 "smoother, no jumps").
// Every line is laid out at the sung line's size and shown smaller by scale when it is not the sung
// one, so a line that wraps when sung wraps the same way before and after it (owner, 10-28): no line
// ever reflows. Each line's space follows its look ([lyricBox]): it grows and shrinks with its scale,
// opens from nothing as the line arrives ([rememberOpen]) and closes as it leaves, misting away
// ([rememberMist]). The lines are plain layout, so whatever moves (the next line, a long line taking
// the sung place, a drag to more or fewer lines) everything around it moves with it, smoothly.

/**
 * The lines shown around the sung one: [at] the sung line, [shown] the window, and [leaving] the lines
 * that just left it and are still dissolving (with the frame time they started).
 */
internal class LyricsDrift(
    val at: Int,
    val shown: IntRange,
    val leaving: Map<Int, Long>,
)

/**
 * Follows [index] and the window [behind] and [ahead] of it. A step to the next line, or a new count,
 * lets the lines that left dissolve for [MIST_MS]; a jump (a seek, a new song) drops them at once.
 */
@Composable
internal fun rememberDrift(
    lyrics: SyncedLyrics,
    index: Int,
    behind: Int,
    ahead: Int,
): LyricsDrift {
    var at by remember(lyrics) { mutableIntStateOf(index) }
    var shown by remember(lyrics) { mutableStateOf((index - behind)..(index + ahead)) }
    var leaving by remember(lyrics) { mutableStateOf(emptyMap<Int, Long>()) }
    LaunchedEffect(lyrics, index, behind, ahead) {
        val next = (index - behind)..(index + ahead)
        if (next == shown && index == at) return@LaunchedEffect
        val dissolve = index == at || index == at + 1
        val now = withFrameMillis { it }
        val gone = if (dissolve) shown.filter { it !in next }.associateWith { now } else emptyMap()
        leaving = if (dissolve) (leaving + gone).filterKeys { it !in next } else emptyMap()
        shown = next
        at = index
    }
    LaunchedEffect(leaving) {
        val oldest = leaving.values.minOrNull() ?: return@LaunchedEffect
        delay((oldest + MIST_MS - withFrameMillis { it }).coerceAtLeast(0L))
        val now = withFrameMillis { it }
        leaving = leaving.filterValues { now - it < MIST_MS }
    }
    return LyricsDrift(at, shown, leaving)
}

/** 0 to 1 as a line arrives (its space opens and it condenses in), back to 0 as it leaves. */
@Composable
internal fun rememberOpen(leaving: Boolean): Animatable<Float, AnimationVector1D> {
    val open = remember { Animatable(0f) }
    LaunchedEffect(leaving) { open.animateTo(if (leaving) 0f else 1f, tween(GLIDE_MS, easing = FastOutSlowInEasing)) }
    return open
}

/** 0 to 1 while the line leaves: it rises, spreads and blurs away, as the loading screen's old words do. */
@Composable
internal fun rememberMist(leaving: Boolean): Animatable<Float, AnimationVector1D> {
    val mist = remember { Animatable(0f) }
    LaunchedEffect(leaving) { if (leaving) mist.animateTo(1f, tween(MIST_MS.toInt(), easing = LinearOutSlowInEasing)) }
    return mist
}

/**
 * A lyric line's space: its laid-out height at [scale] (the text is laid out at the sung size and drawn
 * scaled from its top-left), plus [gapPx], times [open]. A line leaving upward ([rise]) keeps its foot
 * on the line below as its space closes, so it slides up out of the block; any other line opens and
 * closes downward from its top.
 */
internal fun Modifier.lyricBox(
    scale: () -> Float,
    open: () -> Float,
    gapPx: Int,
    rise: Boolean,
): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val full = placeable.height * scale() + gapPx
        val height = (full * open()).roundToInt().coerceAtLeast(0)
        layout(placeable.width, height) { placeable.place(0, if (rise) (height - full).roundToInt() else 0) }
    }

/**
 * A lyric line's look at this frame: [dim], drawn at [scale] from the text's top-left (inset [roomPx]
 * in its layer), condensing in as it [open]s and misting away with [mist].
 */
internal fun GraphicsLayerScope.lyricMotion(
    dim: Float,
    scale: Float,
    open: Float,
    mist: Float,
    roomPx: Float,
) {
    alpha = dim * open * (1f - mist)
    val s = scale * (1f + MIST_SPREAD * mist)
    scaleX = s
    scaleY = s
    if (size.width > 0f && size.height > 0f) transformOrigin = TransformOrigin(roomPx / size.width, roomPx / size.height)
    translationY = -MIST_RISE.toPx() * mist
    val radius = MIST_BLUR.toPx() * mist + EMERGE_BLUR.toPx() * (1f - open)
    renderEffect = if (radius >= MIN_BLUR_PX) BlurEffect(radius, radius, TileMode.Decal) else null
}

/** How long a line takes to change size, or to open and close. */
internal const val GLIDE_MS = 650

// A leaving line's dissolve: EtherealStatus's MIST_MS, rise, blur and spread, so the two read as one family.
private const val MIST_MS = 900L
private val MIST_RISE = 22.dp
private val MIST_BLUR = 14.dp
private const val MIST_SPREAD = 0.08f
private val EMERGE_BLUR = 6.dp
private const val MIN_BLUR_PX = 0.5f
