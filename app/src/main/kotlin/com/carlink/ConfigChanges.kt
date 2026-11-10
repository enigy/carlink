package com.carlink

import android.content.pm.ActivityInfo

/**
 * Configuration-change bits by name, for the log of an activity rebuild: which bits [android.content.res.Configuration.diff]
 * reports between the outgoing activity's configuration and the incoming one's, and which of them the manifest's
 * `configChanges` doesn't declare (those are the ones that rebuilt it). The 10 Nov drive's rebuild at the head
 * unit's wake had no such line, so the trigger was left to inference.
 */
object ConfigChanges {
    /** Hidden in [ActivityInfo]: the window's bounds and windowing mode. */
    private const val WINDOW_CONFIGURATION = 0x20000000

    /** Hidden in [ActivityInfo]: an overlay or assets change. No app can declare it. */
    private const val ASSETS_PATHS = 0x80000000.toInt()

    private val NAMES: List<Pair<Int, String>> =
        listOf(
            ActivityInfo.CONFIG_MCC to "mcc",
            ActivityInfo.CONFIG_MNC to "mnc",
            ActivityInfo.CONFIG_LOCALE to "locale",
            ActivityInfo.CONFIG_TOUCHSCREEN to "touchscreen",
            ActivityInfo.CONFIG_KEYBOARD to "keyboard",
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN to "keyboardHidden",
            ActivityInfo.CONFIG_NAVIGATION to "navigation",
            ActivityInfo.CONFIG_ORIENTATION to "orientation",
            ActivityInfo.CONFIG_SCREEN_LAYOUT to "screenLayout",
            ActivityInfo.CONFIG_UI_MODE to "uiMode",
            ActivityInfo.CONFIG_SCREEN_SIZE to "screenSize",
            ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE to "smallestScreenSize",
            ActivityInfo.CONFIG_DENSITY to "density",
            ActivityInfo.CONFIG_LAYOUT_DIRECTION to "layoutDirection",
            ActivityInfo.CONFIG_COLOR_MODE to "colorMode",
            ActivityInfo.CONFIG_GRAMMATICAL_GENDER to "grammaticalGender",
            ActivityInfo.CONFIG_FONT_WEIGHT_ADJUSTMENT to "fontWeightAdjustment",
            WINDOW_CONFIGURATION to "windowConfiguration",
            ActivityInfo.CONFIG_FONT_SCALE to "fontScale",
            ASSETS_PATHS to "assetsPaths",
        )

    /** The names of the bits in [mask], in [ActivityInfo]'s order; a bit with no name shows in hex. */
    fun names(mask: Int): List<String> {
        val named = NAMES.filter { (bit, _) -> mask and bit != 0 }.map { it.second }
        val rest = NAMES.fold(mask) { m, (bit, _) -> m and bit.inv() }
        return if (rest == 0) named else named + "0x%08x".format(rest)
    }

    /** One line: what changed and, of that, what the manifest doesn't declare. */
    fun describe(
        changed: Int,
        declared: Int,
    ): String {
        if (changed == 0) return "no configuration difference (an overlay or assets change, or a recreate())"
        val undeclared = changed and declared.inv()
        val left = if (undeclared == 0) "none" else names(undeclared).joinToString()
        return "changed ${names(changed).joinToString()}; not declared: $left"
    }
}
