package com.carlink.ui.pane

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.carlink.media.SyncedLyrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// The music card's lyric motion ([LyricsLines], owner 10-27 "Drift", 10-28 "smoother, no jumps"), kept
// apart from the layout it animates. Each line is its own element, keyed by its place in the song, and
// carries its own motion: it glides from where it stood to where it stands now ([glidePlacement]), eases
// between the sung size and the others ([rememberRoleScale]), condenses in when it first shows
// ([rememberEntrance]) and mists away when it falls off the top ([rememberMist]). So nothing jumps,
// whatever moved it: the next line, a long line wrapping onto two or unwrapping to one, or a drag that
// changes how many lines show.

/** The lines the column is laid out around: [at] the sung one, [leaving] the one dissolving off the top after a step, if any. */
internal class LyricsDrift(
    val at: Int,
    val leaving: Int?,
)

/**
 * Follows [index]. A step to the very next line keeps the old top line ([behind] places above the old
 * sung one) as [LyricsDrift.leaving] for the mist's length; anything else (a seek, a new song)
 * moves without one.
 */
@Composable
internal fun rememberDrift(
    lyrics: SyncedLyrics,
    index: Int,
    behind: Int,
): LyricsDrift {
    var at by remember(lyrics) { mutableIntStateOf(index) }
    var leaving by remember(lyrics) { mutableStateOf<Int?>(null) }
    LaunchedEffect(lyrics, index) {
        if (index == at) return@LaunchedEffect
        val stepped = index == at + 1
        leaving = if (stepped) at - behind else null
        at = index
        if (stepped) {
            delay(MIST_MS.toLong())
            leaving = null
        }
    }
    return LyricsDrift(at, leaving)
}

/**
 * Glides the element from where it last stood in its parent to where it stands now, over [GLIDE_MS],
 * whatever moved it. The first placement lands as it is. Compose's own placement-animation pattern:
 * the offset holds the element at its old place on the frame the layout moves, then runs to zero.
 */
@Composable
internal fun Modifier.glidePlacement(): Modifier {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<Float?>(null) }
    val glide = remember { arrayOfNulls<Animatable<Float, AnimationVector1D>>(1) }
    return this
        .onPlaced { target = it.positionInParent().y }
        .offset {
            val to = target ?: return@offset IntOffset.Zero
            val anim = glide[0] ?: Animatable(to).also { glide[0] = it }
            if (anim.targetValue != to) scope.launch { anim.animateTo(to, tween(GLIDE_MS, easing = FastOutSlowInEasing)) }
            IntOffset(0, (anim.value - to).roundToInt())
        }
}

/**
 * The line's scale while it changes between the sung style and the others: on the change it starts at
 * the size it had (the sung style is [ratio] times larger) and eases to 1, so it grows or shrinks
 * rather than snapping. A line's first showing starts at 1.
 */
@Composable
internal fun rememberRoleScale(
    sung: Boolean,
    ratio: Float,
): Animatable<Float, AnimationVector1D> {
    val shown = remember { BooleanArray(1) }
    val from =
        when {
            !shown[0] -> 1f
            sung -> 1f / ratio
            else -> ratio
        }
    val scale = remember(sung) { Animatable(from) }
    shown[0] = true
    LaunchedEffect(sung) { scale.animateTo(1f, tween(GLIDE_MS, easing = FastOutSlowInEasing)) }
    return scale
}

/** 0 to 1 as a line first shows: it condenses out of a soft blur. */
@Composable
internal fun rememberEntrance(): Animatable<Float, AnimationVector1D> {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(GLIDE_MS, easing = FastOutSlowInEasing)) }
    return enter
}

/** 0 to 1 while the line falls off the top: it rises, spreads and blurs away, as the loading screen's old words do. */
@Composable
internal fun rememberMist(leaving: Boolean): Animatable<Float, AnimationVector1D> {
    val mist = remember { Animatable(0f) }
    LaunchedEffect(leaving) { if (leaving) mist.animateTo(1f, tween(MIST_MS, easing = LinearOutSlowInEasing)) else mist.snapTo(0f) }
    return mist
}

/** A lyric line's look at this frame: its dimness, its role scale, its entrance and its mist. */
internal fun GraphicsLayerScope.lyricMotion(
    dim: Float,
    role: Float,
    enter: Float,
    mist: Float,
) {
    alpha = dim * enter * (1f - mist)
    val scale = role * (1f + MIST_SPREAD * mist)
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin(0f, 0.5f)
    translationY = -MIST_RISE.toPx() * mist
    val radius = MIST_BLUR.toPx() * mist + EMERGE_BLUR.toPx() * (1f - enter)
    renderEffect = if (radius >= MIN_BLUR_PX) BlurEffect(radius, radius, TileMode.Decal) else null
}

/** Lays the line out with no height and draws it one slot above, so the leaving line takes no room. */
internal fun Modifier.above(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
    }

/** How long a line takes to glide, change size or show; [MIST_MS] for the top line's dissolve. */
internal const val GLIDE_MS = 650

// The top line's dissolve: EtherealStatus's MIST_MS, rise, blur and spread, so the two read as one family.
private const val MIST_MS = 900
private val MIST_RISE = 22.dp
private val MIST_BLUR = 14.dp
private const val MIST_SPREAD = 0.08f
private val EMERGE_BLUR = 6.dp
private const val MIN_BLUR_PX = 0.5f
