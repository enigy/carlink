package com.carlink.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
 * back out to it. [progress] 0 = fully off the left edge, 1 = in place. While it moves, a shadow on
 * its leading edge keeps it reading as a panel passing over CarPlay.
 */
internal fun Modifier.settingsSlideIn(progress: () -> Float): Modifier =
    graphicsLayer {
        val p = progress().coerceIn(0f, 1f)
        translationX = SettingsSlideIn.offsetFraction(p) * size.width
        shadowElevation = if (p < 1f) SettingsSlideIn.EDGE_SHADOW_DP.dp.toPx() else 0f
    }

/**
 * Timing and path of the settings slide. The caller animates p with [ENTER_MS] / [EXIT_MS] using
 * Material's emphasized easings: entering decelerates into place, leaving accelerates away.
 */
internal object SettingsSlideIn {
    const val ENTER_MS = 400
    const val EXIT_MS = 300
    const val EDGE_SHADOW_DP = 24f

    /** Horizontal offset as a share of the page width: -1 = one full width to the left, 0 = in place. */
    fun offsetFraction(p: Float): Float = p.coerceIn(0f, 1f) - 1f
}
