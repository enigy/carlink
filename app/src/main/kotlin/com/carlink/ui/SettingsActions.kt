package com.carlink.ui

import androidx.compose.runtime.Immutable
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.LayoutConfig

/**
 * What the Settings screen can ask of its host (MainActivity via [CarlinkApp]).
 *
 * @property onNavigateBack close the overlay and return to the projection.
 * @property onDisplayModeSelected persist a new [DisplayMode] and rebuild the session for it — or,
 *   while a phone session owns the box, only persist it ([DisplayModeOutcome.DEFERRED]).
 * @property onLayoutSelected the same for Settings › Display › Layout ([LayoutConfig]): the split's
 *   view areas travel in the pushed config, so a change is a new session too.
 */
@Immutable
class SettingsActions(
    val onNavigateBack: () -> Unit,
    val onDisplayModeSelected: (DisplayMode) -> DisplayModeOutcome,
    val onLayoutSelected: (LayoutConfig) -> DisplayModeOutcome = { DisplayModeOutcome.APPLIED },
    /** Carlink3: tear down and re-establish the Templates Host cluster binding (MainActivity). */
    val onResetCluster: () -> Unit = {},
)

/** What happened to a display-mode choice (MacHost's mid-session rule, `OCBMClient.swift`). */
enum class DisplayModeOutcome {
    /** Applied now: the session was rebuilt for the new content area. */
    APPLIED,

    /** Persisted only; applies at the next connection because a phone session is active. */
    DEFERRED,
}
