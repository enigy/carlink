package com.carlink.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The settings page's arrival progress, provided by CarlinkApp while it animates the page in and
 * out. Read in the layer phase (inside graphicsLayer), so the animation never recomposes. Without a
 * provider the page is simply in place.
 */
internal val LocalSettingsArrival = staticCompositionLocalOf<() -> Float> { { 1f } }

/**
 * The whole settings page (frosted glass and content together) slides in from the left edge and
 * back out to it. [progress] 0 = fully off the left edge, 1 = in place.
 *
 * Built to stay smooth on a head unit's GPU:
 *  - the outer layer only translates, so a frame of motion re-records nothing;
 *  - while moving, the page is rendered into one offscreen buffer and that buffer is slid, instead
 *    of redrawing the blurred glass and every row each frame (back to normal once in place);
 *  - the leading-edge shade is a fixed gradient drawn just past the page's right edge, recorded
 *    once. A real elevation shadow on a full-screen layer is recomputed every frame it moves, and
 *    was the main source of jagged motion. In place, the gradient sits beyond the screen edge.
 */
internal fun Modifier.settingsSlideIn(progress: () -> Float): Modifier =
    this
        .graphicsLayer { translationX = SettingsSlideIn.offsetFraction(progress()) * size.width }
        .drawBehind {
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
 * Timing and path of the settings slide: 1.5 s each way on an ease-in-out cubic, so the page
 * eases off the edge, glides, and settles, with no burst of speed in the first frames.
 */
internal object SettingsSlideIn {
    const val ENTER_MS = 1500
    const val EXIT_MS = 1500
    const val EDGE_SHADE_DP = 32f
    const val EDGE_SHADE_ALPHA = 0.35f

    /** easeInOutCubic: slow start, steady middle, slow finish. */
    val EASING: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

    /** Horizontal offset as a share of the page width: -1 = one full width to the left, 0 = in place. */
    fun offsetFraction(p: Float): Float = p.coerceIn(0f, 1f) - 1f
}
