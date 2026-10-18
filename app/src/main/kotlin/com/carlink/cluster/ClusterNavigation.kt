package com.carlink.cluster

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.carlink.logging.Logger
import com.carlink.logging.logInfo

/**
 * The Cluster Navigation switch (Carlink3). Carlink2 kept this in its AdapterConfigPreference; OCBM's
 * app has no such store, so it is a one-key SharedPreferences file here, in the style of
 * [com.carlink.ui.settings.DisplayModeStore].
 *
 * Off by default: driving the gauge cluster is an explicit opt-in, as in Carlink2. The switch also
 * enables/disables the [CarlinkClusterService] component (declared `enabled="false"` in the
 * manifest), so the Templates Host only discovers the navigation CarAppService when the user wants
 * it. MainActivity applies the component state at start-up and launches the binding chain when on.
 */
object ClusterNavigation {
    private const val PREFS = "carlink_cluster_nav"
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
        logInfo("[CLUSTER] Cluster navigation ${if (enabled) "enabled" else "disabled"}", tag = Logger.Tags.CLUSTER)
        applyComponentState(context)
    }

    /** Mirror the preference onto the CarAppService component so the host only sees it when on. */
    fun applyComponentState(context: Context) {
        val app = context.applicationContext
        val component = ComponentName(app, CarlinkClusterService::class.java)
        val wanted =
            if (isEnabled(app)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        if (app.packageManager.getComponentEnabledSetting(component) != wanted) {
            app.packageManager.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            val label = if (wanted == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) "enabled" else "disabled"
            logInfo("[CLUSTER] CarlinkClusterService component -> $label", tag = Logger.Tags.CLUSTER)
        }
    }
}
