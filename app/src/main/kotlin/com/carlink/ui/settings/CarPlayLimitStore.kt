package com.carlink.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings › Display › Limit CarPlay while driving. On (the default): when Android says the truck is
 * being driven (UX restrictions, which GM derives from gear and speed), Carlink3 tells CarPlay to
 * limit its UI, and CarPlay hides the keyboard and shortens lists. Off: CarPlay is never told, so it
 * stays as it is when parked, for a passenger using it on the move (asked for 2026-10-23). Apps that
 * limit themselves while driving (Messages) still do.
 *
 * Kept per iPhone ([DriverProfiles]), like the Cluster settings: [reload] switches to the active
 * driver's file when an iPhone identifies itself, and a new iPhone starts with the limit on.
 */
class CarPlayLimitStore private constructor(
    context: Context,
    /** The driver whose file this is ([DriverProfiles.View.other]), or null for the active driver's: the shared instance. */
    private val mac: String? = null,
) {
    @Volatile private var prefs = open(context)
    private val state = MutableStateFlow(read(prefs))

    val flow: StateFlow<Boolean> = state.asStateFlow()

    /** Whether CarPlay is told to limit its UI while driving. */
    val limitWhileDriving: Boolean get() = state.value

    fun setLimitWhileDriving(on: Boolean) {
        prefs.edit().putBoolean(KEY_LIMIT, on).apply()
        state.value = on
    }

    /** The active driver changed: read their setting. */
    fun reload(context: Context) {
        prefs = open(context)
        state.value = read(prefs)
    }

    private fun open(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(DriverProfiles.fileName(context, PREFS_NAME, mac), Context.MODE_PRIVATE)

    private fun read(p: SharedPreferences): Boolean = p.getBoolean(KEY_LIMIT, true)

    companion object {
        // PERSISTENCE CONTRACT: renaming either orphans the stored choice. Profiles add "@<mac>" to
        // the file name ([DriverProfiles]).
        internal const val PREFS_NAME = "carlink_carplay_limit"
        private const val KEY_LIMIT = "limit_while_driving"

        @Volatile private var instance: CarPlayLimitStore? = null

        fun get(context: Context): CarPlayLimitStore =
            instance ?: synchronized(this) {
                instance ?: CarPlayLimitStore(context).also { instance = it }
            }

        /** What Settings edits: the shared store, or another iPhone's while parked ([DriverProfiles.edit]). */
        fun of(
            context: Context,
            view: DriverProfiles.View,
        ): CarPlayLimitStore = view.other?.let { CarPlayLimitStore(context, it.mac) } ?: get(context)
    }
}
