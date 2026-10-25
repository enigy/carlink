package com.carlink.ui.settings

import android.content.Context
import com.carlink.ocbm.PixelRect
import com.carlink.ocbm.SplitViewArea
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings › Display › Layout: the whole panel for CarPlay, or CarPlay on one side with Carlink3's
 * own pane (music, a call, a clock) on the other — Android Auto's dual view, built on CarPlay's own
 * view areas ([SplitViewArea]). The truck's setting, not an iPhone's: it is about the panel.
 * Persisted by [code], never ordinal.
 */
enum class PaneLayout(
    val code: String,
    override val label: String,
    val summary: String,
) : Labelled {
    FULL("full", "Full screen", "CarPlay uses the whole panel"),
    MEDIA_RIGHT("right", "Media on right", "CarPlay left, Carlink3 pane right"),
    MEDIA_LEFT("left", "Media on left", "Carlink3 pane left, CarPlay right"),
    ;

    val split: Boolean get() = this != FULL

    val paneOnLeft: Boolean get() = this == MEDIA_LEFT

    companion object {
        /** Full screen: exactly what every install did before the layout existed. */
        val DEFAULT = FULL

        fun fromCode(code: String?): PaneLayout = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/** How much of the panel the pane takes; CarPlay keeps the rest (1680 px of 2400 at 30 %). */
enum class PaneWidth(
    val code: String,
    override val label: String,
    val percent: Int,
) : Labelled {
    NARROW("25", "25 %", 25),
    STANDARD("30", "30 %", 30),
    WIDE("35", "35 %", 35),
    ;

    companion object {
        val DEFAULT = STANDARD

        fun fromCode(code: String?): PaneWidth = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/**
 * The layout and the pane width together: what one session is built for. [paneCornerDp] is the
 * pane's corner radius, matched by eye to CarPlay's on the panel; it only changes how the pane is
 * drawn, so a change applies at once and never rebuilds the session ([sameSession]).
 */
data class LayoutConfig(
    val layout: PaneLayout = PaneLayout.DEFAULT,
    val width: PaneWidth = PaneWidth.DEFAULT,
    val paneCornerDp: Int = DEFAULT_CORNER_DP,
) {
    val split: Boolean get() = layout.split

    /** Whether [other] declares the same view areas: what a session rebuild depends on. */
    fun sameSession(other: LayoutConfig): Boolean = layout == other.layout && width == other.width

    /** CarPlay's view area on a declared [panelW] x [panelH] panel; null for full screen or when no legal area exists. */
    fun carPlayArea(
        panelW: Int,
        panelH: Int,
    ): PixelRect? = if (split) SplitViewArea.carPlayArea(panelW, panelH, layout.paneOnLeft, width.percent) else null

    companion object {
        const val DEFAULT_CORNER_DP = 48
        val CORNER_RANGE_DP = 8..128
    }
}

/** The persisted [LayoutConfig], in the style of [DriveSideStore]. One instance per process. */
class LayoutStore private constructor(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state =
        MutableStateFlow(
            LayoutConfig(
                layout = PaneLayout.fromCode(prefs.getString(KEY_LAYOUT, null)),
                width = PaneWidth.fromCode(prefs.getString(KEY_WIDTH, null)),
                paneCornerDp = prefs.getInt(KEY_CORNER, LayoutConfig.DEFAULT_CORNER_DP).coerceIn(LayoutConfig.CORNER_RANGE_DP),
            ),
        )

    val flow: StateFlow<LayoutConfig> = state.asStateFlow()

    val current: LayoutConfig get() = state.value

    fun set(config: LayoutConfig) {
        prefs
            .edit()
            .putString(KEY_LAYOUT, config.layout.code)
            .putString(KEY_WIDTH, config.width.code)
            .putInt(KEY_CORNER, config.paneCornerDp)
            .apply()
        state.value = config
    }

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these orphans the stored choice.
        private const val PREFS_NAME = "carlink_layout"
        private const val KEY_LAYOUT = "layout"
        private const val KEY_WIDTH = "pane_width"
        private const val KEY_CORNER = "pane_corner_dp"

        @Volatile private var instance: LayoutStore? = null

        fun get(context: Context): LayoutStore =
            instance ?: synchronized(this) {
                instance ?: LayoutStore(context).also { instance = it }
            }
    }
}
