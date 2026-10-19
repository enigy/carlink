package com.carlink.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The settings page's arrival progress, provided by CarlinkApp while it animates the page in and
 * out. Read in the layer phase (inside graphicsLayer), so the animation never recomposes. Without a
 * provider the page is simply in place.
 */
internal val LocalSettingsArrival = staticCompositionLocalOf<() -> Float> { { 1f } }

/**
 * Whether the settings page is open. The page stays composed (parked off the left edge) while
 * closed, so opening it only slides an image that is already drawn; this tells its parts when to
 * poll, take Back, show in accessibility and drop half-finished dialogs. Dynamic, so a change only
 * recomposes the readers.
 */
internal val LocalSettingsVisible = compositionLocalOf { true }

/** Runs [onHidden] each time the settings page closes: dialogs and previews must not outlive it. */
@Composable
internal fun OnSettingsHidden(onHidden: () -> Unit) {
    val visible = LocalSettingsVisible.current
    val latest = rememberUpdatedState(onHidden)
    LaunchedEffect(visible) { if (!visible) latest.value() }
}

/**
 * The whole settings page (frosted glass and content together) slides in from the left edge and
 * back out to it. [progress] 0 = fully off the left edge, 1 = in place.
 *
 * Built to stay smooth on a head unit's GPU:
 *  - the outer layer only translates, so a frame of motion re-records nothing;
 *  - while off place, the page is rendered into one offscreen buffer and that buffer is slid,
 *    instead of redrawing the blurred glass and every row each frame (normal compositing in place);
 *  - the leading-edge shade is a plain gradient drawn just past the page's right edge, only while
 *    the page moves; it re-records two ops a frame. A real elevation shadow on a full-screen layer
 *    is recomputed every frame it moves.
 */
internal fun Modifier.settingsSlideIn(progress: () -> Float): Modifier =
    this
        .graphicsLayer { translationX = SettingsSlideIn.offsetFraction(progress()) * size.width }
        .drawBehind {
            // Only while moving: parked at p = 0 the strip would sit on the screen's left edge.
            // Reading progress here re-records just this layer's two ops, not the page.
            val p = progress()
            if (p <= 0f || p >= 1f) return@drawBehind
            val edge = SettingsSlideIn.EDGE_SHADE_DP.dp.toPx()
            drawRect(
                brush =
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = SettingsSlideIn.EDGE_SHADE_ALPHA),
                        1f to Color.Transparent,
                        startX = size.width,
                        endX = size.width + edge,
                    ),
                topLeft = Offset(size.width, 0f),
                size = Size(edge, size.height),
            )
        }.graphicsLayer {
            compositingStrategy = if (progress() < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }

/**
 * Timing and path of the settings slide. Entering decelerates (it starts moving on its first
 * frame and settles gently); leaving accelerates away. Material's "emphasized" pair.
 */
internal object SettingsSlideIn {
    const val ENTER_MS = 500
    const val EXIT_MS = 350
    const val EDGE_SHADE_DP = 32f
    const val EDGE_SHADE_ALPHA = 0.35f

    /** A frame this much later than the 60 Hz period counts as late in the slide's frame log. */
    private const val LATE_FRAME_MS = 25.0
    private const val NANOS_PER_MS = 1_000_000.0

    val ENTER_EASING: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EXIT_EASING: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Horizontal offset as a share of the page width: -1 = one full width to the left, 0 = in place. */
    fun offsetFraction(p: Float): Float = p.coerceIn(0f, 1f) - 1f

    /**
     * One line for the trail: frame count, duration, the longest gap between frames and where it
     * fell, and how many gaps were late. [frameNanos] are the frame-clock times seen during a slide.
     */
    fun frameSummary(frameNanos: List<Long>): String {
        if (frameNanos.size < 2) return "frames=${frameNanos.size}"
        val gaps = frameNanos.zipWithNext { a, b -> (b - a) / NANOS_PER_MS }
        val longest = gaps.withIndex().maxBy { it.value }
        val late = gaps.count { it > LATE_FRAME_MS }
        val total = ((frameNanos.last() - frameNanos.first()) / NANOS_PER_MS).roundToInt()
        val longestTenths = (longest.value * 10).roundToInt() / 10.0
        return "frames=${frameNanos.size} span=${total}ms " +
            "longestGap=${longestTenths}ms@#${longest.index + 1} late(>${LATE_FRAME_MS.toInt()}ms)=$late"
    }

    /** Runs [block] while recording every frame-clock tick, then returns [frameSummary] of them. */
    suspend fun measureFrames(block: suspend () -> Unit): String =
        coroutineScope {
            val stamps = ArrayList<Long>()
            val recorder = launch { while (isActive) withFrameNanos { stamps += it } }
            try {
                block()
            } finally {
                recorder.cancel()
            }
            frameSummary(stamps)
        }
}
