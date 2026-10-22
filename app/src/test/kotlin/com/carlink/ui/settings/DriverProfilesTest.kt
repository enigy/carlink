package com.carlink.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.carlink.cluster.ClusterNavigation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Per-driver settings: each iPhone its own Turn alert, Lane guidance and Directions on cluster. */
@RunWith(RobolectricTestRunner::class)
class DriverProfilesTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val scott = "aa:bb:cc:dd:ee:01"
    private val guest = "aa:bb:cc:dd:ee:02"

    @Before
    fun setUp() = DriverProfiles.resetForTest()

    @After
    fun tearDown() = DriverProfiles.resetForTest()

    /** Settings saved before profiles, in the shared file [name]. */
    private fun seedShared(
        name: String,
        vararg values: Pair<String, Any>,
    ) {
        val edit = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        values.forEach { (k, v) -> if (v is Boolean) edit.putBoolean(k, v) else edit.putString(k, v.toString()) }
        edit.commit()
    }

    private fun turnAlert() = TurnAlertStore.get(ctx).apply { reload(ctx) }

    private fun lanes() = LaneGuidanceStore.get(ctx).apply { reload(ctx) }

    @Test
    fun beforeAnyIphoneTheSharedFilesAreUsed() {
        assertNull(DriverProfiles.active(ctx))
        assertEquals(TurnAlertStore.PREFS_NAME, DriverProfiles.fileName(ctx, TurnAlertStore.PREFS_NAME))
    }

    @Test
    fun theFirstIphoneKeepsTheSettingsFromBeforeProfiles() {
        seedShared(TurnAlertStore.PREFS_NAME, "enabled" to true, "color" to "amber")
        seedShared(ClusterNavigation.PREFS, "enabled" to false)
        assertTrue(DriverProfiles.onPhone(ctx, scott, "Scott's iPhone"))
        assertTrue(turnAlert().current.enabled)
        assertEquals(InlayColor.AMBER, turnAlert().current.color)
        assertFalse(ClusterNavigation.isEnabled(ctx))
    }

    @Test
    fun aNewIphoneStartsFromFactoryDefaults() {
        seedShared(TurnAlertStore.PREFS_NAME, "enabled" to true)
        seedShared(ClusterNavigation.PREFS, "enabled" to false)
        DriverProfiles.onPhone(ctx, scott, "Scott's iPhone")
        DriverProfiles.onPhone(ctx, guest, "Guest")
        assertFalse(turnAlert().current.enabled)
        val l = lanes().current
        assertTrue(l.ask)
        assertTrue(l.strip)
        assertEquals(LaneIconMode.OFF, l.icon)
        assertNull(l.laneColor)
        assertTrue(ClusterNavigation.isEnabled(ctx))
    }

    @Test
    fun eachIphoneKeepsItsOwnSettings() {
        DriverProfiles.onPhone(ctx, scott, "Scott's iPhone")
        turnAlert().setEnabled(true)
        lanes().setLaneColor(InlayColor.GREEN)
        DriverProfiles.onPhone(ctx, guest, "Guest")
        assertFalse(turnAlert().current.enabled)
        assertNull(lanes().current.laneColor)
        DriverProfiles.onPhone(ctx, scott, "Scott's iPhone")
        assertTrue(turnAlert().current.enabled)
        assertEquals(InlayColor.GREEN, lanes().current.laneColor)
    }

    @Test
    fun theLastDriverIsRememberedAcrossRestarts() {
        DriverProfiles.onPhone(ctx, scott, "Scott's iPhone")
        DriverProfiles.resetForTest()
        assertEquals(DriverProfiles.Profile(scott, "Scott's iPhone"), DriverProfiles.active(ctx))
        assertFalse("the same iPhone again changes nothing", DriverProfiles.onPhone(ctx, scott, ""))
        assertEquals("Scott's iPhone", DriverProfiles.active(ctx)?.name)
    }

    @Test
    fun theClusterPageSaysWhoseSettingsTheseAre() {
        val p = DriverProfiles.Profile(scott, "Scott's iPhone")
        assertEquals(
            "Shared by every phone until an iPhone connects. Then each iPhone keeps its own Cluster settings.",
            DriverProfiles.describe(null, null),
        )
        assertEquals("For Scott's iPhone. Each iPhone keeps its own Cluster settings.", DriverProfiles.describe(p, scott))
        assertEquals(
            "For Scott's iPhone, the last iPhone connected. Each iPhone keeps its own Cluster settings.",
            DriverProfiles.describe(p, null),
        )
    }
}
