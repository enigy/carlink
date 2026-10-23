package com.carlink.ui.settings

import android.content.Context
import android.content.SharedPreferences
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
 *
 * Phase 2 (2026-10-23): while parked, Settings can show another iPhone's profile ([edit]), copy one
 * profile over another and reset one to factory defaults; Forget on the Phones page deletes the
 * phone's profile. The runtime always reads the active profile; only Settings follows [edit].
 */
object DriverProfiles {
    /** One driver: the phone's MAC (lower case, with colons) and the name it gave, possibly empty. */
    data class Profile(
        val mac: String,
        val name: String,
    ) : Labelled {
        override val label: String get() = name.ifEmpty { "iPhone ${mac.takeLast(MAC_TAIL)}" }
    }

    /**
     * What Settings shows: [shown] is the profile its per-driver rows read and write (null = the
     * shared files, before any iPhone), [active] the one in use, [all] every profile, the one in use
     * first. [revision] changes when a copy or reset rewrote a profile.
     */
    data class View(
        val shown: Profile?,
        val active: Profile?,
        val all: List<Profile>,
        val revision: Int,
    ) {
        /** The shown profile when it is not the one in use, else null. */
        val other: Profile? get() = shown?.takeIf { it.mac != active?.mac }

        /** The line under the profile bar saying whose settings these are; [connectedMac] is the phone in session. */
        fun describe(connectedMac: String?): String =
            when {
                shown == null -> "Shared by every phone until an iPhone connects. Then each iPhone keeps its own Cluster and Driving settings."
                other != null -> "Not in use now. Changes apply the next time this iPhone connects."
                shown.mac == connectedMac -> "Connected now. Each iPhone keeps its own Cluster and Driving settings."
                else -> "The last iPhone connected. In use until another one connects."
            }
    }

    private const val MAC_TAIL = 5

    private val state = MutableStateFlow<Profile?>(null)
    private val view = MutableStateFlow(View(null, null, emptyList(), 0))

    /** The MAC Settings is showing instead of the active driver, or null to follow the active one. */
    @Volatile private var editing: String? = null

    @Volatile private var loaded = false

    /** The active driver, or null before any iPhone has identified itself. */
    fun active(context: Context): Profile? {
        load(context)
        return state.value
    }

    /** What Settings shows, for the per-driver rows and the profile bar. */
    fun view(context: Context): StateFlow<View> {
        load(context)
        return view.asStateFlow()
    }

    /**
     * The file the per-driver setting group [base] is kept in for the driver [mac], or for the active
     * driver when [mac] is null.
     */
    fun fileName(
        context: Context,
        base: String,
        mac: String? = null,
    ): String = (mac ?: active(context)?.mac)?.let { ProfileFiles.fileFor(base, it) } ?: base

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
        val prefs = ProfileFiles.index(app)
        val was = state.value
        val before = ProfileFiles.known(prefs).firstOrNull { it.mac == mac }
        val keptName = name.ifEmpty { before?.name.orEmpty() }
        if (!prefs.contains(ProfileFiles.KEY_LEGACY_OWNER)) {
            ProfileFiles.adoptShared(app, mac)
            prefs.edit().putString(ProfileFiles.KEY_LEGACY_OWNER, mac).apply()
        }
        ProfileFiles.record(prefs, Profile(mac, keptName), active = true)
        state.value = Profile(mac, keptName)
        // The iPhone being edited just connected: Settings now shows it as the one in use.
        if (editing == mac) editing = null
        publish(app)
        return was?.mac != mac
    }

    /** Show [mac]'s profile in Settings (parked only; the caller checks), or the active one for null. */
    @Synchronized
    fun edit(
        context: Context,
        mac: String?,
    ) {
        load(context)
        editing = mac?.takeIf { m -> m != state.value?.mac && view.value.all.any { it.mac == m } }
        publish(context)
    }

    /** Overwrite [to]'s settings with [from]'s. */
    @Synchronized
    fun copy(
        context: Context,
        from: String,
        to: String,
    ) {
        if (from == to) return
        val app = context.applicationContext
        ProfileFiles.copyAll(app, from, to)
        rewritten(app, to)
    }

    /** Back to factory defaults: directions on, lane guidance on, turn alert off, CarPlay limited while driving. */
    @Synchronized
    fun reset(
        context: Context,
        mac: String,
    ) {
        val app = context.applicationContext
        ProfileFiles.clear(app, mac)
        rewritten(app, mac)
    }

    /**
     * Forget on the Phones page: the phone's profile goes with it. Forgetting the active driver
     * leaves the shared settings in use until the next iPhone connects.
     */
    @Synchronized
    fun forget(
        context: Context,
        mac: String,
    ) {
        load(context)
        val app = context.applicationContext
        val prefs = ProfileFiles.index(app)
        if (ProfileFiles.known(prefs).none { it.mac == mac }) return
        ProfileFiles.delete(app, mac)
        val wasActive = state.value?.mac == mac
        ProfileFiles.drop(prefs, mac, active = wasActive)
        if (editing == mac) editing = null
        if (wasActive) {
            state.value = null
            reloadActive(app)
        }
        publish(app)
    }

    /**
     * Re-read the active driver's settings into the stores the runtime holds, after the active
     * profile changed or was rewritten.
     */
    fun reloadActive(context: Context) {
        TurnAlertStore.get(context).reload(context)
        LaneGuidanceStore.get(context).reload(context)
        // Its flow change re-sends limitedUI for the new driver (the collector beside the drive monitor).
        CarPlayLimitStore.get(context).reload(context)
        ClusterNavigation.applyComponentState(context)
    }

    private fun rewritten(
        app: Context,
        mac: String,
    ) {
        if (state.value?.mac == mac) reloadActive(app)
        view.value = view.value.copy(revision = view.value.revision + 1)
    }

    private fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = ProfileFiles.index(context)
            state.value = ProfileFiles.active(prefs)
            loaded = true
            publish(context)
        }
    }

    private fun publish(context: Context) {
        val active = state.value
        val all = ProfileFiles.known(ProfileFiles.index(context)).asReversed().sortedByDescending { it.mac == active?.mac }
        val shown = editing?.let { m -> all.firstOrNull { it.mac == m } } ?: active
        view.value = View(shown, active, all, view.value.revision)
    }

    /** Forget the loaded state (tests: each has a fresh application). */
    internal fun resetForTest() {
        loaded = false
        editing = null
        state.value = null
        view.value = View(null, null, emptyList(), 0)
    }
}

/** Where profiles live: the index file (who is active, every profile's name) and each one's setting files. */
private object ProfileFiles {
    private const val PREFS = "carlink_driver_profiles"
    private const val KEY_MAC = "active_mac"
    private const val KEY_NAME = "active_name"
    const val KEY_LEGACY_OWNER = "legacy_owner"

    /** Every profile's MAC, oldest first, comma-separated; each name is under [KEY_NAME_OF] + MAC. */
    private const val KEY_KNOWN = "known"
    private const val KEY_NAME_OF = "name@"

    /** The setting groups kept per driver, by their shared file's name. */
    private val PER_DRIVER = listOf(TurnAlertStore.PREFS_NAME, LaneGuidanceStore.PREFS_NAME, ClusterNavigation.PREFS, CarPlayLimitStore.PREFS_NAME)

    fun index(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun fileFor(
        base: String,
        mac: String,
    ): String = "$base@${mac.replace(":", "")}"

    fun active(prefs: SharedPreferences): DriverProfiles.Profile? {
        val mac = prefs.getString(KEY_MAC, null) ?: return null
        return DriverProfiles.Profile(mac, prefs.getString(KEY_NAME, null).orEmpty())
    }

    /**
     * Every profile, oldest first. Profiles from before the list existed (v27-v30) are not in it;
     * the active driver stands for them, since that is the one profile those versions made sure of.
     */
    fun known(prefs: SharedPreferences): List<DriverProfiles.Profile> {
        val macs = prefs.getString(KEY_KNOWN, null)?.split(',')?.filter { it.isNotEmpty() }
        if (macs == null) return listOfNotNull(active(prefs))
        return macs.map { DriverProfiles.Profile(it, prefs.getString(KEY_NAME_OF + it, null).orEmpty()) }
    }

    /** Add or refresh [p] as the newest profile, and make it the active one when [active]. */
    fun record(
        prefs: SharedPreferences,
        p: DriverProfiles.Profile,
        active: Boolean,
    ) {
        val others = known(prefs).filter { it.mac != p.mac }
        val edit =
            prefs
                .edit()
                .putString(KEY_KNOWN, (others.map { it.mac } + p.mac).joinToString(","))
                .putString(KEY_NAME_OF + p.mac, p.name)
        others.forEach { edit.putString(KEY_NAME_OF + it.mac, it.name) }
        if (active) edit.putString(KEY_MAC, p.mac).putString(KEY_NAME, p.name)
        edit.apply()
    }

    /** Remove [mac] from the index; when it was the [active] driver, no one is. */
    fun drop(
        prefs: SharedPreferences,
        mac: String,
        active: Boolean,
    ) {
        val others = known(prefs).filter { it.mac != mac }
        val edit =
            prefs
                .edit()
                .putString(KEY_KNOWN, others.joinToString(",") { it.mac })
                .remove(KEY_NAME_OF + mac)
        others.forEach { edit.putString(KEY_NAME_OF + it.mac, it.name) }
        if (active) edit.remove(KEY_MAC).remove(KEY_NAME)
        edit.apply()
    }

    /** The first iPhone after profiles arrived takes over the shared settings. */
    fun adoptShared(
        app: Context,
        mac: String,
    ) = PER_DRIVER.forEach { copyInto(app, it, fileFor(it, mac)) }

    fun copyAll(
        app: Context,
        from: String,
        to: String,
    ) = PER_DRIVER.forEach { base ->
        wipe(app, fileFor(base, to))
        copyInto(app, fileFor(base, from), fileFor(base, to))
    }

    /** An empty file reads as the factory defaults. */
    fun clear(
        app: Context,
        mac: String,
    ) = PER_DRIVER.forEach { wipe(app, fileFor(it, mac)) }

    /** Cleared first: the process keeps an open SharedPreferences, which a deleted file would not empty. */
    fun delete(
        app: Context,
        mac: String,
    ) {
        clear(app, mac)
        PER_DRIVER.forEach { app.deleteSharedPreferences(fileFor(it, mac)) }
    }

    private fun wipe(
        app: Context,
        name: String,
    ) {
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    /** The settings in [from] into the file [to], unless it already has some. */
    private fun copyInto(
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
        edit.commit()
    }
}
