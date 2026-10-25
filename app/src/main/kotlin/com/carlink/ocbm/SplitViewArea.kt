package com.carlink.ocbm

/**
 * The CarPlay view area of the split layout (Settings › Display › Layout): CarPlay keeps one side of
 * the panel, full height, and Carlink3's own pane takes the rest. Declared as the main stream's
 * `viewAreas[1]` with `initial: true`, so the session starts split; CarPlay's Dock button (and the
 * pane's own) switch between it and the full panel ([Ocbm.CMD_VIEW_AREA]).
 *
 * Derived from the DECLARED panel — the content area, even, inside [PanelRule]'s envelope — never from
 * the physical panel: with a system bar visible the panel is 2400x788, and an area derived from 960
 * would fail containment, which iOS answers with a session teardown (`info.rs::view_area_2` refuses it
 * first). The width rounds DOWN to even, as every value must be ([ViewAreaRule]), and an area that is
 * illegal or below the product floor is simply not declared: the session comes up full screen.
 */
object SplitViewArea {
    /**
     * The CarPlay rectangle for a pane of [panePercent] of the panel on the left or the right, or null
     * when no legal one exists for this panel. On the truck's 2400x960: 30 % → 1680x960 @ 0,0 for a
     * pane on the right, @ 720,0 for one on the left (device-proven shape, box log 2026-09-05).
     */
    fun carPlayArea(
        panelW: Int,
        panelH: Int,
        paneOnLeft: Boolean,
        panePercent: Int,
    ): PixelRect? {
        if (panePercent !in 1 until PERCENT) return null
        val carW = (panelW * (PERCENT - panePercent) / PERCENT) and 1.inv()
        val x = if (paneOnLeft) panelW - carW else 0
        val a = PixelRect(x, 0, carW, panelH)
        if (ViewAreaRule.teardownVerdict(a, panelW, panelH) != null) return null
        if (ViewAreaRule.floorVerdict(a.width, a.height) != null) return null
        return a
    }

    private const val PERCENT = 100
}
