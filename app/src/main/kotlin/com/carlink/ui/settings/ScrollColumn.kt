package com.carlink.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.carlink.logging.logInfo
import kotlinx.coroutines.launch

/** Where the thumb sits in its track, in pixels. */
internal data class ThumbGeometry(
    val top: Int,
    val height: Int,
)

/** Thumb maths for [ScrollColumn], kept free of Compose so it can be tested. */
internal object ScrollThumb {
    /**
     * The thumb is the visible share of the content, never shorter than [minThumb] (so it stays
     * visible and readable on a long page), and travels the rest of the track as the page scrolls.
     */
    fun geometry(
        track: Int,
        value: Int,
        max: Int,
        viewport: Int,
        minThumb: Int,
    ): ThumbGeometry {
        if (track <= 0 || max <= 0 || viewport <= 0) return ThumbGeometry(0, track.coerceAtLeast(0))
        val share = viewport.toFloat() / (viewport + max)
        val height = (track * share).toInt().coerceIn(minOf(minThumb, track), track)
        val top = ((track - height) * (value.coerceIn(0, max).toFloat() / max)).toInt()
        return ThumbGeometry(top, height)
    }

    /** One tap moves most of a screen, keeping a line of context from the last one. */
    fun pageStep(viewport: Int): Float = viewport * PAGE_FRACTION

    private const val PAGE_FRACTION = 0.8f
}

/**
 * Settings scroll cue, design "Scroll cue 2" (the AAOS car UI scroll bar): a column on the right
 * of the detail pane with large up and down buttons that page the content and a thumb between
 * them. Shown only when the page is taller than the pane. Buttons dim at the ends. Taps, not
 * drags, so it works with gloves and at a glance; the page still scrolls by touch as before.
 */
@Composable
internal fun ScrollColumn(
    state: ScrollState,
    modifier: Modifier = Modifier,
) {
    if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE) return
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val page = { dir: Int ->
        scope.launch {
            logInfo("[UI_ACTION] Settings page ${if (dir < 0) "up" else "down"}", tag = "UI")
            state.animateScrollBy(dir * ScrollThumb.pageStep(state.viewportSize))
        }
    }
    Column(
        modifier = modifier.fillMaxHeight().width(128.dp).padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PageButton(Icons.Default.KeyboardArrowUp, "Scroll up", enabled = state.value > 0) { page(-1) }
        var track by remember { mutableIntStateOf(0) }
        val minThumb = with(LocalDensity.current) { 64.dp.roundToPx() }
        val thumb = ScrollThumb.geometry(track, state.value, state.maxValue, state.viewportSize, minThumb)
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .width(10.dp)
                    .background(colors.surfaceContainerHighest, RoundedCornerShape(5.dp))
                    .onSizeChanged { track = it.height },
        ) {
            val thumbHeight = with(LocalDensity.current) { thumb.height.toDp() }
            Box(
                modifier =
                    Modifier
                        .offset { IntOffset(0, thumb.top) }
                        .size(width = 10.dp, height = thumbHeight)
                        .background(colors.onSurfaceVariant, RoundedCornerShape(5.dp)),
            )
        }
        PageButton(Icons.Default.KeyboardArrowDown, "Scroll down", enabled = state.value < state.maxValue) { page(1) }
    }
}

@Composable
private fun PageButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) colors.secondaryContainer else colors.surfaceContainerHigh,
        contentColor = if (enabled) colors.onSecondaryContainer else colors.onSurface.copy(alpha = DISABLED_ALPHA),
        modifier = Modifier.size(88.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(48.dp))
        }
    }
}

private const val DISABLED_ALPHA = 0.38f
