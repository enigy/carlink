package com.carlink.cluster

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the Templates Host needs from the merged manifest before it will give the cluster a session.
 * Up to v10 the app-level minCarApiLevel was missing; the host's getAppInfo threw and the cluster
 * stayed empty with nothing in the app's own log.
 */
@RunWith(RobolectricTestRunner::class)
class ClusterManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun minCarApiLevelIsDeclaredAtApplicationLevel() {
        assertEquals(6, ClusterNavigation.declaredMinCarApiLevel(context))
    }

    @Test
    fun clusterServiceShipsEnabled() {
        val component = ComponentName(context, CarlinkClusterService::class.java)
        val info = context.packageManager.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        assertTrue(info.enabled)
    }

    @Test
    fun clusterNavigationIsOnByDefaultAndKeepsAnExplicitChoice() {
        assertTrue(ClusterNavigation.isEnabled(context))
        ClusterNavigation.setEnabled(context, false)
        assertFalse(ClusterNavigation.isEnabled(context))
        val component = ComponentName(context, CarlinkClusterService::class.java)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(component))
    }
}
