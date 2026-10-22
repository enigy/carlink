package com.carlink.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * The settings detail pane's scroll, for content that keeps itself in view while its section scrolls
 * past ([stickyIn]). [viewport] is the pane's visible area, set by the pane.
 */
internal class PaneScroll(
    val state: ScrollState,
) {
    var viewport: LayoutCoordinates? = null
}

/** The pane scroll around the current content; null outside a settings detail pane. */
internal val LocalPaneScroll = compositionLocalOf<PaneScroll?> { null }

/** Where a section sits on the page, independent of scrolling: its top and its height, in px. */
@Stable
internal class StickySection {
    var top by mutableIntStateOf(0)
    var height by mutableIntStateOf(0)
}

/** Records this section's place on the page for [stickyIn]. Does nothing outside a detail pane. */
internal fun Modifier.trackSection(
    section: StickySection,
    pane: PaneScroll?,
): Modifier =
    if (pane == null) {
        this
    } else {
        onGloballyPositioned { coords ->
            val viewport = pane.viewport?.takeIf { it.isAttached } ?: return@onGloballyPositioned
            // Its position in the viewport moves with the scroll; adding the scroll back gives its
            // place on the page, which only changes when something above it changes size.
            section.top = (viewport.localPositionOf(coords, Offset.Zero).y + pane.state.value).roundToInt()
            section.height = coords.size.height
        }
    }

/**
 * Keeps this element in view while its [section] scrolls past (Settings › Cluster › Turn alert's
 * preview, 2026-10-22): it starts at the section's top, rides down the section [margin] below the
 * top of the pane as the page scrolls, and stops at the section's bottom, so it leaves with the
 * section. The offset is worked out at placement from the live scroll position, so it moves in the
 * same frame as the page.
 */
internal fun Modifier.stickyIn(
    section: StickySection,
    pane: PaneScroll?,
    margin: Dp,
): Modifier =
    if (pane == null) {
        this
    } else {
        layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                val travel = (section.height - placeable.height).coerceAtLeast(0)
                val y = (pane.state.value + margin.roundToPx() - section.top).coerceIn(0, travel)
                placeable.place(0, y)
            }
        }
    }
