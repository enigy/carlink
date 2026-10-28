package com.carlink.ui.settings

import android.content.Context

/**
 * The saved settings a session is built from, so a change takes effect only at a new connection: the
 * display mode and the layout shape the window and the view areas, and the driver's seat and lane
 * guidance travel in the config pushed at SUBSCRIBE. Leaving Settings after changing one asks whether
 * to reset the connection now or keep the change for the next connection (owner, 10-28).
 */
data class SessionSettings(
    val displayMode: DisplayMode,
    val layout: LayoutConfig,
    val driveSide: DriveSide,
    val askLanes: Boolean,
) {
    /** The settings, by their names in Settings, whose value in [other] differs from this one's. */
    fun differences(other: SessionSettings): List<String> =
        buildList {
            if (displayMode != other.displayMode) add(DISPLAY_MODE)
            if (layout != other.layout) add(LAYOUT)
            if (driveSide != other.driveSide) add(DRIVER_SEAT)
            if (askLanes != other.askLanes) add(ASK_LANES)
        }

    companion object {
        const val DISPLAY_MODE = "Display mode"
        const val LAYOUT = "Layout"
        const val DRIVER_SEAT = "Driver's seat"
        const val ASK_LANES = "Ask iPhone for lanes"

        /** What is saved now; lane guidance is the active driver's. */
        fun saved(context: Context): SessionSettings =
            SessionSettings(
                displayMode = DisplayModeStore(context).get(),
                layout = LayoutStore.get(context).current,
                driveSide = DriveSideStore.get(context).current,
                askLanes = LaneGuidanceStore.get(context).current.ask,
            )

        /**
         * What leaving Settings asks about: every setting the session in use was not [built] with, when
         * at least one of them changed since Settings [opened]. Empty means just close: no session,
         * nothing waiting, a change put back, or only changes the driver already put off on an earlier visit.
         */
        fun toAsk(
            built: SessionSettings?,
            opened: SessionSettings,
            saved: SessionSettings,
        ): List<String> {
            val waiting = built?.differences(saved).orEmpty()
            val changedNow = opened.differences(saved)
            return if (waiting.any { it in changedNow }) waiting else emptyList()
        }
    }
}
