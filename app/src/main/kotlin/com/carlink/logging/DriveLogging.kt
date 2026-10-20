package com.carlink.logging

import android.content.Context

/**
 * Settings → Adapter and about → Record drive logs: the persisted switch behind [DriveLog]. Off by
 * default (it writes to shared storage); once on, recording starts with the app, so a drive is
 * captured without touching the screen. Same one-key SharedPreferences shape as [VerboseLogging].
 */
object DriveLogging {
    private const val PREFS = "carlink_drive_logging"
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
        if (enabled) DriveLog.start(context) else DriveLog.stop()
    }

    /** Startup: begin this run's drive log if the switch is on. */
    fun apply(context: Context) {
        if (isEnabled(context)) DriveLog.start(context)
    }
}
