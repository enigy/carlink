package com.carlink.ocbm

import android.content.Context

/**
 * The adapters, by serial, that have carried a CarPlay session in their trial. A trial must have done so before
 * Carlink finishes its install to make room for an update (Scott, 1 Nov; [finishBlocked]).
 */
class CarPlaySessionRecord(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun serials(): Set<String> = prefs.getStringSet(KEY_SERIALS, null).orEmpty()

    fun contains(serial: String): Boolean = serial.isNotEmpty() && serial in serials()

    fun add(serial: String) {
        if (serial.isEmpty() || contains(serial)) return
        prefs.edit().putStringSet(KEY_SERIALS, serials() + serial).apply()
    }

    private companion object {
        // PERSISTENCE CONTRACT: renaming either forgets which adapters have proven themselves.
        const val PREFS_NAME = "carlink_carplay_sessions"
        const val KEY_SERIALS = "serials"
    }
}
