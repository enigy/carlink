package com.carlink.cluster

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.settings.DriverProfiles

/**
 * The Cluster Navigation switch (Carlink3). Carlink2 kept this in its AdapterConfigPreference; OCBM's
 * app has no such store, so it is a one-key SharedPreferences file here, in the style of
 * [com.carlink.ui.settings.DisplayModeStore].
 *
 * On by default, as in Carlink2 since [194]; an explicit choice the user made is kept. The switch
 * also enables/disables the [CarlinkClusterService] component (declared `enabled="true"` in the
 * manifest to match the default), so the Templates Host only discovers the navigation CarAppService
 * when the user wants it. MainActivity applies the component state at start-up and launches the
 * binding chain when on.
 */
object ClusterNavigation {
    internal const val PREFS = "carlink_cluster_nav"
    private const val KEY_ENABLED = "enabled"
    private const val DEFAULT_ENABLED = true

    /**
     * The Car App library reads this key from the APPLICATION's meta-data only
     * (`AppInfo.retrieveMinCarAppApiLevel`) and throws "Min API level not declared in manifest" when
     * it is missing. That throw happens in the host's `getAppInfo` call and is reported only to the
     * host, so the app's own log never shows it: the cluster simply never gets a session. Up to v10
     * Carlink3 declared it on the service element alone.
     */
    const val MIN_CAR_API_LEVEL_KEY = "androidx.car.app.minCarApiLevel"

    /** The active driver's choice, or the iPhone [mac]'s (Settings showing another profile while parked). */
    fun isEnabled(
        context: Context,
        mac: String? = null,
    ): Boolean = prefs(context, mac).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    /** For another iPhone [mac], only stored: the cluster follows the active driver. */
    fun setEnabled(
        context: Context,
        enabled: Boolean,
        mac: String? = null,
    ) {
        prefs(context, mac)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (mac != null) return
        logInfo("[CLUSTER] Cluster navigation ${if (enabled) "enabled" else "disabled"}", tag = Logger.Tags.CLUSTER)
        applyComponentState(context)
    }

    /**
     * The application-level min Car API level the Templates Host will see, or null when the manifest
     * does not declare it (the cluster can then never get a session).
     */
    fun declaredMinCarApiLevel(context: Context): Int? {
        val app = context.applicationContext
        val meta = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
        return if (meta != null && meta.containsKey(MIN_CAR_API_LEVEL_KEY)) meta.getInt(MIN_CAR_API_LEVEL_KEY) else null
    }

    /** The driver's file ([DriverProfiles]), the active one's unless [mac]: directions are a per-driver choice, on by default. */
    private fun prefs(
        context: Context,
        mac: String? = null,
    ) = context.applicationContext.getSharedPreferences(DriverProfiles.fileName(context, PREFS, mac), Context.MODE_PRIVATE)

    /** Mirror the preference onto the CarAppService component so the host only sees it when on. */
    fun applyComponentState(context: Context) {
        val app = context.applicationContext
        val component = ComponentName(app, CarlinkClusterService::class.java)
        val on = isEnabled(app)
        val wanted = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (app.packageManager.getComponentEnabledSetting(component) != wanted) {
            app.packageManager.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
        }
        val minApi = declaredMinCarApiLevel(app)
        logInfo("[CLUSTER] CarlinkClusterService component ${if (on) "enabled" else "disabled"} (app minCarApiLevel=$minApi)", tag = Logger.Tags.CLUSTER)
        if (on && minApi == null) {
            logWarn("[CLUSTER] $MIN_CAR_API_LEVEL_KEY missing from application meta-data — the Templates Host cannot start a cluster session", tag = Logger.Tags.CLUSTER)
        }
    }
}
