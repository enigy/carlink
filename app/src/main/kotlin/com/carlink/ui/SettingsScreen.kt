package com.carlink.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.CarlinkManager
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.adaptive.DashboardArrangement
import com.carlink.ui.adaptive.DashboardLayout
import com.carlink.ui.adaptive.asWindowInsets
import com.carlink.ui.adaptive.rememberWindowLayoutInfo
import com.carlink.ui.components.FrostedGlassBackground
import com.carlink.ui.components.LocalSettingsArrival
import com.carlink.ui.components.LocalSettingsVisible
import com.carlink.ui.components.OnSettingsHidden
import com.carlink.ui.components.settingsSlideIn
import com.carlink.ui.settings.AdapterPane
import com.carlink.ui.settings.BackgroundLogoConfig
import com.carlink.ui.settings.BackgroundLogoStore
import com.carlink.ui.settings.CallAudioStore
import com.carlink.ui.settings.CallsPane
import com.carlink.ui.settings.ClusterPane
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.DisplayModeDeferredNotice
import com.carlink.ui.settings.DisplayModeStore
import com.carlink.ui.settings.DisplayPane
import com.carlink.ui.settings.LaneGuidanceStore
import com.carlink.ui.settings.LocalPaneScroll
import com.carlink.ui.settings.PaneScroll
import com.carlink.ui.settings.PhonesPane
import com.carlink.ui.settings.ScrollColumn
import com.carlink.ui.settings.SettingsCategory
import com.carlink.ui.settings.SettingsLiveState
import com.carlink.ui.settings.SettingsModel
import com.carlink.ui.settings.SettingsStyle
import com.carlink.ui.settings.TroubleshootPane
import com.carlink.ui.settings.rememberSettingsLiveState
import com.carlink.ui.theme.LocalCarlinkColors
import com.carlink.util.EdgeInsets

/**
 * Settings (design "Merged A"): one page with a top bar (back, title, the connected phone, close
 * app), a category list whose rows carry live summaries, and the selected category's pane. It
 * replaces the old Phones / Control tabs. Composed OVER [MainScreen] so the video surface underneath
 * survives; closing it goes through [SettingsActions.onNavigateBack], which also nudges the decoder
 * (`CarlinkManager.recoverVideoFromOverlay`) so a frame that went stale under the overlay is replaced.
 *
 * A wide landscape window shows the list and the pane side by side (the dashboard breakpoint,
 * [DashboardLayout]); anything narrower shows the list, then the pane with a back row.
 */
@Composable
fun SettingsScreen(
    carlinkManager: CarlinkManager,
    connectionState: CarlinkManager.State,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    LaunchedEffect(visible) {
        if (visible) logInfo("[UI_STATE] SettingsScreen opened - user is in app settings (NOT viewing CarPlay projection)", tag = "UI")
    }
    val live = rememberSettingsLiveState(carlinkManager, connectionState, active = visible)

    // The detected safe area (cutout / waterfall / corner arcs) pads in addition to the live bars.
    val safeArea = carlinkManager.displayProfile?.safeAreaInsets ?: EdgeInsets.NONE

    // Frosted glass over the connecting screen's brand scene; glass and content slide in together
    // from the left. The Surface stays transparent but sets LocalContentColor for un-coloured Text.
    val backgroundLogo by BackgroundLogoStore.get(LocalContext.current).flow.collectAsState()
    val arrival = LocalSettingsArrival.current
    // Parked off-screen while closed: keep it out of accessibility too.
    val hiddenSemantics = if (visible) Modifier else Modifier.clearAndSetSemantics {}
    CompositionLocalProvider(LocalSettingsVisible provides visible) {
        SettingsPage(
            panes = PaneContext(carlinkManager, live, connectionState != CarlinkManager.State.DISCONNECTED, actions),
            backgroundLogo = backgroundLogo,
            safeArea = safeArea,
            modifier = modifier.fillMaxSize().settingsSlideIn(arrival).then(hiddenSemantics),
        )
    }
}

/** The page itself: frosted glass under the top bar and the list + pane body. */
@Composable
private fun SettingsPage(
    panes: PaneContext,
    backgroundLogo: BackgroundLogoConfig,
    safeArea: EdgeInsets,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        FrostedGlassBackground(backgroundLogo = backgroundLogo)
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.union(safeArea.asWindowInsets())),
        ) {
            SettingsTopBar(
                phone = panes.live.connectedPhone,
                onBack = panes.actions.onNavigateBack,
                onCloseApp = {
                    logWarn("[UI_ACTION] Close App confirmed", tag = "UI")
                    // Off the main thread (USB release + CT_STOP block), under the lifecycle mutex;
                    // the button finishes the Activity only once this returns.
                    panes.carlinkManager.disconnect()
                },
            )
            SettingsBody(panes = panes, modifier = Modifier.weight(1f))
        }
    }
}

/** What every pane may need, gathered so the composables between here and the panes stay short. */
@Stable
private class PaneContext(
    val carlinkManager: CarlinkManager,
    val live: SettingsLiveState,
    val connected: Boolean,
    val actions: SettingsActions,
)

/** List + pane, side by side on a wide window and one after the other on a narrow one. */
@Composable
private fun SettingsBody(
    panes: PaneContext,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val window = rememberWindowLayoutInfo()
    val twoPane = DashboardLayout.arrangement(window) == DashboardArrangement.TWO_PANE
    var selected by rememberSaveable { mutableStateOf(SettingsCategory.PHONES) }
    var paneOpen by rememberSaveable { mutableStateOf(false) }
    // The persisted mode, not the host's copy: a mode saved for the next connection is the choice.
    var savedMode by remember { mutableStateOf(DisplayModeStore(context).get()) }
    var showDeferredNotice by remember { mutableStateOf(false) }
    val visible = LocalSettingsVisible.current
    OnSettingsHidden {
        showDeferredNotice = false
        paneOpen = false
    }
    val summaries = categorySummaries(panes, savedMode)

    LaunchedEffect(selected) { logInfo("[UI_STATE] Settings category: $selected", tag = "UI") }
    val pick: (SettingsCategory) -> Unit = {
        selected = it
        paneOpen = true
    }
    val applyMode: (DisplayMode) -> Unit = { mode ->
        val outcome = panes.actions.onDisplayModeSelected(mode)
        savedMode = mode
        if (outcome == DisplayModeOutcome.DEFERRED) showDeferredNotice = true
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (twoPane) {
            Row(modifier = Modifier.fillMaxSize()) {
                CategoryList(selected, summaries, onSelect = pick, modifier = Modifier.width((window.widthDp * 0.33f).dp.coerceIn(360.dp, 780.dp)))
                DetailPane(selected, modifier = Modifier.weight(1f)) { CategoryPane(selected, panes, savedMode, applyMode) }
            }
        } else if (!paneOpen) {
            CategoryList(selected, summaries, onSelect = pick, modifier = Modifier.fillMaxWidth())
        } else {
            BackHandler(enabled = visible) { paneOpen = false }
            DetailPane(selected, modifier = Modifier.fillMaxSize()) {
                BackToCategories(onClick = { paneOpen = false })
                CategoryPane(selected, panes, savedMode, applyMode)
            }
        }
        if (showDeferredNotice) DisplayModeDeferredNotice(onDismiss = { showDeferredNotice = false })
    }
}

@Composable
private fun CategoryPane(
    category: SettingsCategory,
    panes: PaneContext,
    savedMode: DisplayMode,
    onApplyMode: (DisplayMode) -> Unit,
) {
    when (category) {
        SettingsCategory.PHONES -> PhonesPane(panes.carlinkManager, panes.live)
        SettingsCategory.DISPLAY -> DisplayPane(savedMode = savedMode, onApply = onApplyMode)
        SettingsCategory.CALLS -> CallsPane(panes.carlinkManager)
        SettingsCategory.CLUSTER -> ClusterPane(panes.live, onResetCluster = panes.actions.onResetCluster)
        SettingsCategory.TROUBLESHOOT -> TroubleshootPane(panes.carlinkManager, panes.live, connected = panes.connected)
        SettingsCategory.ADAPTER -> AdapterPane(panes.carlinkManager, panes.live)
    }
}

/** Each category's one-line live summary, so most questions are answered before a tap. */
@Composable
private fun categorySummaries(
    panes: PaneContext,
    savedMode: DisplayMode,
): Map<SettingsCategory, String> {
    val context = LocalContext.current
    val logo by remember { BackgroundLogoStore.get(context) }.flow.collectAsState()
    val callAudio = remember { CallAudioStore.get(context) }
    val mic by callAudio.callMicFlow.collectAsState()
    val buffer by callAudio.callBufferFlow.collectAsState()
    val laneGuidance by remember { LaneGuidanceStore.get(context) }.flow.collectAsState()
    val live = panes.live
    return mapOf(
        SettingsCategory.PHONES to SettingsModel.phonesSummary(live.connectedPhone, live.devices.size),
        SettingsCategory.DISPLAY to SettingsModel.displaySummary(savedMode, logo),
        SettingsCategory.CALLS to SettingsModel.callsSummary(mic, buffer),
        SettingsCategory.CLUSTER to SettingsModel.clusterSummary(live.clusterOn, laneGuidance.anyOn),
        SettingsCategory.TROUBLESHOOT to SettingsModel.troubleshootSummary(live.driveLog),
        SettingsCategory.ADAPTER to SettingsModel.adapterSummary(panes.carlinkManager.adapterName, live.box),
    )
}

@Composable
private fun CategoryList(
    selected: SettingsCategory,
    summaries: Map<SettingsCategory, String>,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 40.dp, end = 32.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SettingsCategory.entries.forEach { category ->
            CategoryItem(category, summaries[category].orEmpty(), selected = category == selected, onClick = { onSelect(category) })
        }
    }
}

@Composable
private fun CategoryItem(
    category: SettingsCategory,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val extra = LocalCarlinkColors.current
    val fg = if (selected) colors.onPrimaryContainer else colors.onSurface
    Surface(
        selected = selected,
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        },
        shape = RoundedCornerShape(28.dp),
        color = if (selected) colors.primaryContainer else Color.Transparent,
        contentColor = fg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 112.dp).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(20.dp),
                color = if (selected) extra.selectedTile else colors.surfaceContainerHigh,
                contentColor = if (selected) extra.onSelectedTile else fg,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(category.icon, contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(category.title, fontSize = 27.sp, style = SettingsStyle.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    summary,
                    style = SettingsStyle.RowSubtitle,
                    fontSize = 21.sp,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(30.dp))
        }
    }
}

/** The right-hand pane: a lifted surface that scrolls, its scroll reset per category, with [ScrollColumn] beside it. */
@Composable
private fun DetailPane(
    category: SettingsCategory,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(topStart = 40.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
    ) {
        key(category) {
            val scroll = rememberScrollState()
            val pane = remember(scroll) { PaneScroll(scroll) }
            Row {
                CompositionLocalProvider(LocalPaneScroll provides pane) {
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .onGloballyPositioned { pane.viewport = it }
                                .verticalScroll(scroll)
                                .padding(start = 56.dp, end = 32.dp, top = 40.dp, bottom = 40.dp),
                        content = content,
                    )
                }
                // Scroll cue 2: page buttons and a thumb, only when the page is taller than the pane.
                ScrollColumn(scroll)
            }
        }
    }
}

@Composable
private fun BackToCategories(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 64.dp).padding(bottom = 16.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(28.dp))
        Text("All settings", style = SettingsStyle.Action, modifier = Modifier.padding(start = 12.dp))
    }
}
