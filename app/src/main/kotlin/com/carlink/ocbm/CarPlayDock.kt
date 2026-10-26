package com.carlink.ocbm

import com.carlink.ocbm.seam.ViewAreaRect

/**
 * CarPlay's Dock: the strip of CarPlay's own area on the driver's side, with the time, the recent apps
 * and Home. A tap there is how a driver leaves the app in front, and it is the one CarPlay app switch
 * this app can see: iOS names no foreground app to the accessory (the drive logs to 10-26 carry only
 * `modesChanged`, `disableBluetooth` and `requestUI`), and the box has nothing more to forward.
 *
 * The Dock's width is not measured. CarPlay sizes its icons to the screen's height, so it is taken as
 * [SHARE] of the area's height, about an inch on the truck's panel; taps near it are logged with their
 * depth ([com.carlink.CarlinkManager.onCarPlayTap]), so one drive says where the Dock really ends.
 */
object CarPlayDock {
    /** The Dock's width as a share of CarPlay's area height: 192 px of 960. */
    const val SHARE = 0.2f

    /** The Dock's width in frame pixels. */
    fun width(area: ViewAreaRect): Float = area.height * SHARE

    /**
     * How far in from the Dock's outer edge a point at [x] (0..1 of the frame's width) is, in frame
     * pixels. The Dock is on the left of CarPlay's area, or on the right when [onRight] (a right-hand
     * drive car). Negative is outside CarPlay's area: on the pane, or past the frame.
     */
    fun depth(
        area: ViewAreaRect,
        onRight: Boolean,
        x: Float,
    ): Float {
        val px = x * area.codedWidth
        return if (onRight) area.x + area.width - px else px - area.x
    }

    /** Whether a point at [x], [y] (0..1 of the frame) is on the Dock of CarPlay's [area]. */
    fun contains(
        area: ViewAreaRect,
        onRight: Boolean,
        x: Float,
        y: Float,
    ): Boolean {
        val py = y * area.codedHeight
        return depth(area, onRight, x) in 0f..width(area) && py >= area.y && py <= area.y + area.height
    }
}
