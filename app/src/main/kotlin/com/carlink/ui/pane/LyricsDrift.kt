package com.carlink.ui.pane

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.carlink.media.SyncedLyrics

// The music card's step from one lyric line to the next ([LyricsLines], owner 10-27: prototype C,
// "Drift"), kept apart from the layout it animates.

/** Where the column stands in a step to the next line: [at] the line it is laid out for, and the step's phases. */
internal class LyricsDrift(
    val at: Int,
    progress: Float,
    stepped: Boolean,
) {
    val stepping = stepped && progress < 1f

    /** The slide up a slot and the sung line's growth, done at [GLIDE_MS]. */
    val glide = if (stepping) FastOutSlowInEasing.transform((progress * DRIFT_MS / GLIDE_MS).coerceAtMost(1f)) else 1f

    /** The top line's dissolve, the whole step long. */
    val mist = if (stepping) LinearOutSlowInEasing.transform(progress) else 1f

    /** The new bottom line's arrival. */
    val fade = if (stepping) FastOutSlowInEasing.transform(progress) else 1f
}

/** Follows [index]: a step to the very next line plays the drift, anything else lands at once. */
@Composable
internal fun rememberDrift(
    lyrics: SyncedLyrics,
    index: Int,
): LyricsDrift {
    var at by remember(lyrics) { mutableIntStateOf(index) }
    var stepped by remember(lyrics) { mutableStateOf(false) }
    val progress = remember(lyrics) { Animatable(1f) }
    LaunchedEffect(lyrics, index) {
        if (index == at) return@LaunchedEffect
        stepped = index == at + 1
        at = index
        if (stepped) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(DRIFT_MS, easing = LinearEasing))
        } else {
            progress.snapTo(1f)
        }
    }
    return LyricsDrift(at, progress.value, stepped)
}

/** The line falling off the top: it rises, spreads and blurs away, as the loading screen's old words do. */
internal fun GraphicsLayerScope.mist(m: Float) {
    alpha = 1f - m
    translationY = -MIST_RISE.toPx() * m
    val scale = 1f + MIST_SPREAD * m
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin(0f, 0.5f)
    blur(MIST_BLUR.toPx() * m)
}

/** The new sung line growing from the size it had as the next one. */
internal fun GraphicsLayerScope.lift(g: Float) {
    val scale = LIFT_FROM + (1f - LIFT_FROM) * g
    scaleX = scale
    scaleY = scale
    alpha = LIFT_ALPHA + (1f - LIFT_ALPHA) * g
    transformOrigin = TransformOrigin(0f, 0.5f)
}

/** The new bottom line, condensing out of a soft blur. */
internal fun GraphicsLayerScope.emerge(f: Float) {
    alpha = f
    blur(EMERGE_BLUR.toPx() * (1f - f))
}

private fun GraphicsLayerScope.blur(radius: Float) {
    renderEffect = if (radius >= MIN_BLUR_PX) BlurEffect(radius, radius, TileMode.Decal) else null
}

/** Lays the line out with no height and draws it one slot above, so the leaving line takes no room. */
internal fun Modifier.above(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
    }

// The step to the next line: the glide takes the first GLIDE_MS of DRIFT_MS, the mist all of it
// (EtherealStatus's MIST_MS, rise, blur and spread, so the two read as one family).
private const val DRIFT_MS = 900
private const val GLIDE_MS = 650
private val MIST_RISE = 22.dp
private val MIST_BLUR = 14.dp
private const val MIST_SPREAD = 0.08f
private val EMERGE_BLUR = 6.dp
private const val LIFT_FROM = 0.8f
private const val LIFT_ALPHA = 0.55f
private const val MIN_BLUR_PX = 0.5f
