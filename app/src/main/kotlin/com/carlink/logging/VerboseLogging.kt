package com.carlink.logging

import android.content.Context

/**
 * Settings → App Control → Verbose Logs: lifts the release build's WARN/ERROR-only logcat filter so
 * the INFO lines ([CLUSTER], [NAVI], [BRIDGE], [MIC], …) reach the file Save Logs writes. Off by
 * default; persisted so a drive can be set up before leaving. Same one-key SharedPreferences shape
 * as `ClusterNavigation`. Applied by [Logger.setReleaseVerbose] at startup and on toggle.
 */
object VerboseLogging {
    private const val PREFS = "carlink_verbose_logging"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        Logger.setReleaseVerbose(enabled)
    }

    /** Startup: make the saved choice effective before anything logs. */
    fun apply(context: Context) = Logger.setReleaseVerbose(isEnabled(context))
}
