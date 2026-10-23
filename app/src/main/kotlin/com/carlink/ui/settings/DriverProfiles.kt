package com.carlink.ui.settings

import android.content.Context
import com.carlink.cluster.ClusterNavigation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-driver settings (scoped 2026-10-22, iPhones first): each iPhone keeps its own Turn alert, Lane
 * guidance, Directions on cluster and (2026-10-23) Limit CarPlay while driving, keyed by its
 * `deviceID` (the BR/EDR MAC in `CT_PHONE_IDENT`, the same for a wired and a wireless connection).
 * The truck's own settings (display mode, driver's seat, loading screen, calls, phones, logs) stay
 * shared.
 *
 * The active profile is the iPhone connected now, else the last one that connected, kept across
 * restarts: the next driver is usually the last one, and "Ask iPhone for lanes" has to be decided
 * before the phone says who it is. Until any iPhone has identified itself the settings stay in the
 * shared files they lived in before profiles.
 *
 * A profile is one more SharedPreferences file per setting group: the shared file's name, "@" and
 * the MAC without colons. A new iPhone starts from factory defaults (an empty file reads as the
 * defaults: directions on, lane guidance on, turn alert off, CarPlay limited while driving), except
 * the first iPhone to connect after profiles arrived, which takes over the shared settings so their
 * owner keeps what they set.
 */
object DriverProfiles {
    /** One driver: the phone's MAC (lower case, with colons) and the name it gave, possibly empty. */
    data class Profile(
        val mac: String,
        val name: String,
    )

    private const val PREFS = "carlink_driver_profiles"
    private const val KEY_MAC = "active_mac"
    private const val KEY_NAME = "active_name"
    private const val KEY_LEGACY_OWNER = "legacy_owner"

    /** The setting groups kept per driver, by their shared file's name. */
    private val PER_DRIVER = listOf(TurnAlertStore.PREFS_NAME, LaneGuidanceStore.PREFS_NAME, ClusterNavigation.PREFS, CarPlayLimitStore.PREFS_NAME)

    private val state = MutableStateFlow<Profile?>(null)

    @Volatile private var loaded = false

    /** The active driver, or null before any iPhone has identified itself. */
    fun active(context: Context): Profile? {
        load(context)
        return state.value
    }

    fun flow(context: Context): StateFlow<Profile?> {
        load(context)
        return state.asStateFlow()
    }

    /** The file the per-driver setting group [base] is kept in for the active driver. */
    fun fileName(
        context: Context,
        base: String,
    ): String = active(context)?.let { fileFor(base, it.mac) } ?: base

    /**
     * `CT_PHONE_IDENT`: the iPhone [mac] is connected. Makes its profile the active one, creating it
     * on its first connection. Returns true when the active profile changed, so the settings it
     * holds must be reloaded.
     */
    @Synchronized
    fun onPhone(
        context: Context,
        mac: String,
        name: String,
    ): Boolean {
        load(context)
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val was = state.value
        val keptName = name.ifEmpty { was?.takeIf { it.mac == mac }?.name.orEmpty() }
        if (!prefs.contains(KEY_LEGACY_OWNER)) {
            PER_DRIVER.forEach { copyIfEmpty(app, it, fileFor(it, mac)) }
            prefs.edit().putString(KEY_LEGACY_OWNER, mac).apply()
        }
        prefs
            .edit()
            .putString(KEY_MAC, mac)
            .putString(KEY_NAME, keptName)
            .apply()
        state.value = Profile(mac, keptName)
        return was?.mac != mac
    }

    private fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            state.value = prefs.getString(KEY_MAC, null)?.let { Profile(it, prefs.getString(KEY_NAME, null).orEmpty()) }
            loaded = true
        }
    }

    private fun fileFor(
        base: String,
        mac: String,
    ): String = "$base@${mac.replace(":", "")}"

    /** The shared settings [from] into the new profile file [to], unless it already has some. */
    private fun copyIfEmpty(
        app: Context,
        from: String,
        to: String,
    ) {
        val source = app.getSharedPreferences(from, Context.MODE_PRIVATE).all
        val target = app.getSharedPreferences(to, Context.MODE_PRIVATE)
        if (source.isEmpty() || target.all.isNotEmpty()) return
        val edit = target.edit()
        source.forEach { (key, value) ->
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
            }
        }
        edit.apply()
    }

    /** The line under Settings › Cluster saying whose settings these are; [connectedMac] is the phone in session. */
    fun describe(
        driver: Profile?,
        connectedMac: String?,
    ): String =
        when {
            driver == null -> "Shared by every phone until an iPhone connects. Then each iPhone keeps its own Cluster settings."
            driver.mac == connectedMac -> "For ${driver.name.ifEmpty { "this iPhone" }}. Each iPhone keeps its own Cluster settings."
            else -> "For ${driver.name.ifEmpty { "the last iPhone" }}, the last iPhone connected. Each iPhone keeps its own Cluster settings."
        }

    /** Forget the loaded state (tests: each has a fresh application). */
    internal fun resetForTest() {
        loaded = false
        state.value = null
    }
}
