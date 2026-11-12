package com.carlink.fuel

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Fuel card's stars (asked 12 Nov): the stations the driver starred, and the tab the card shows, All or Starred.
 * A star keeps only the station's id ([Station.id]: Google's place ID, which Google lets an app keep, or the
 * OpenStreetMap element's), never its name, address, position or price, in Carlink's private preferences until the
 * driver taps the star again. The Starred tab shows the starred stations among the lookup's, so it costs no lookup of
 * its own. A star belongs to its source: a station starred from Google's list isn't starred in OpenStreetMap's.
 */
class StationStars private constructor(
    context: Context,
) {
    /** The card's tabs: every station the lookup found, or the starred ones among them. Persisted by [code]. */
    enum class Tab(
        val code: String,
        val label: String,
    ) {
        ALL("all", "All"),
        STARRED("starred", "Starred"),
    }

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val starredState = MutableStateFlow(prefs.getStringSet(KEY_IDS, null).orEmpty().toSet())
    private val tabState = MutableStateFlow(Tab.entries.firstOrNull { it.code == prefs.getString(KEY_TAB, null) } ?: Tab.ALL)

    /** The starred stations' ids. */
    val starred: StateFlow<Set<String>> = starredState.asStateFlow()

    /** The tab the card shows. */
    val tab: StateFlow<Tab> = tabState.asStateFlow()

    /** Star [id], or take its star away; true when it is starred now. */
    fun toggle(id: String): Boolean {
        val now = starredState.value.let { if (id in it) it - id else it + id }
        prefs.edit().putStringSet(KEY_IDS, now).apply()
        starredState.value = now
        return id in now
    }

    fun setTab(tab: Tab) {
        prefs.edit().putString(KEY_TAB, tab.code).apply()
        tabState.value = tab
    }

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these loses the stars or the tab.
        private const val PREFS_NAME = "carlink_fuel_stars"
        private const val KEY_IDS = "starred_ids"
        private const val KEY_TAB = "tab"

        @Volatile private var instance: StationStars? = null

        fun get(context: Context): StationStars =
            instance ?: synchronized(this) {
                instance ?: StationStars(context).also { instance = it }
            }
    }
}
