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
    fun eachIphoneKeepsItsOwnCarPlayLimit() {
        val limit = CarPlayLimitStore.get(ctx)
        DriverProfiles.onPhone(ctx, scott, "Scott's iPhone")
        limit.reload(ctx)
        assertTrue("limited while driving by default", limit.limitWhileDriving)
        limit.setLimitWhileDriving(false)
        DriverProfiles.onPhone(ctx, guest, "Guest")
        limit.reload(ctx)
        assertTrue("a new iPhone starts limited", limit.limitWhileDriving)
        DriverProfiles.onPhone(ctx, scott, "Scott's iPhone")
        limit.reload(ctx)
        assertFalse(limit.limitWhileDriving)
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
    fun theProfileBarSaysWhoseSettingsTheseAre() {
        val p = DriverProfiles.Profile(scott, "Scott's iPhone")
        val g = DriverProfiles.Profile(guest, "Guest")
        assertEquals(
            "Shared by every phone until an iPhone connects. Then each iPhone keeps its own Cluster and Driving settings.",
            DriverProfiles.View(null, null, emptyList(), 0).describe(null),
        )
        val inUse = DriverProfiles.View(p, p, listOf(p, g), 0)
        assertEquals("Connected now. Each iPhone keeps its own Cluster and Driving settings.", inUse.describe(scott))
        assertEquals("The last iPhone connected. In use until another one connects.", inUse.describe(null))
        val other = DriverProfiles.View(g, p, listOf(p, g), 0)
        assertEquals(g, other.other)
        assertEquals("Not in use now. Changes apply the next time this iPhone connects.", other.describe(scott))
        assertEquals("iPhone ee:02", DriverProfiles.Profile(guest, "").label)
    }

    private val view get() = DriverProfiles.view(ctx).value

    @Test
    fun everyIphoneIsListedTheOneInUseFirst() {
        DriverProfiles.onPhone(ctx, scott, "Scott's iPhone")
        DriverProfiles.onPhone(ctx, guest, "Guest")
        assertEquals(listOf(guest, scott), view.all.map { it.mac })
        DriverProfiles.onPhone(ctx, scott, "")
        assertEquals(listOf(DriverProfiles.Profile(scott, "Scott's iPhone"), DriverProfiles.Profile(guest, "Guest")), view.all)
    }

    @Test
    fun editingAnotherIphoneLeavesTheOneInUseAlone() {
        DriverProfiles.onPhone(ctx, scott, "Scott's iPhone")
        DriverProfiles.onPhone(ctx, guest, "Guest")
        DriverProfiles.edit(ctx, scott)
        assertEquals(scott, view.other?.mac)
        TurnAlertStore.of(ctx, view).setEnabled(true)
        LaneGuidanceStore.of(ctx, view).setLaneColor(InlayColor.GREEN)
        CarPlayLimitStore.of(ctx, view).setLimitWhileDriving(false)
        ClusterNavigation.setEnabled(ctx, false, scott)
        assertFalse("the guest drives on unchanged", turnAlert().current.enabled)
        assertNull(lanes().current.laneColor)
        assertTrue(CarPlayLimitStore.get(ctx).apply { reload(ctx) }.limitWhileDriving)
        assertTrue(ClusterNavigation.isEnabled(ctx))
        DriverProfiles.onPhone(ctx, scott, "Scott's iPhone")
        assertNull("Scott connected: Settings follows the one in use again", view.other)
        assertTrue(turnAlert().current.enabled)
        assertEquals(InlayColor.GREEN, lanes().current.laneColor)
        assertFalse(ClusterNavigation.isEnabled(ctx))
    }

    @Test
    fun editingTheOneInUseIsTheSharedStore() {
        DriverProfiles.onPhone(ctx, scott, "Scott's iPhone")
        DriverProfiles.edit(ctx, scott)
        assertNull(view.other)
        assertTrue(TurnAlertStore.of(ctx, view) === TurnAlertStore.get(ctx))
        DriverProfiles.edit(ctx, "aa:bb:cc:dd:ee:99")
        assertNull("not a profile: nothing to show", view.other)
    }

    @Test
    fun copyReplacesTheTargetsSettings() {
        DriverProfiles.onPhone(ctx, scott, "Scott's iPhone")
        turnAlert().setEnabled(true)
        lanes().setLaneColor(InlayColor.GREEN)
        DriverProfiles.onPhone(ctx, guest, "Guest")
        lanes().setStrip(false)
        val before = view.revision
        DriverProfiles.copy(ctx, scott, guest)
        assertTrue(view.revision > before)
        assertTrue("the guest is in use: its stores re-read", TurnAlertStore.get(ctx).current.enabled)
        assertEquals(InlayColor.GREEN, LaneGuidanceStore.get(ctx).current.laneColor)
        assertTrue("nothing of the guest's own is left", LaneGuidanceStore.get(ctx).current.strip)
    }

    @Test
    fun resetGoesBackToFactoryDefaults() {
        DriverProfiles.onPhone(ctx, scott, "Scott's iPhone")
        turnAlert().setEnabled(true)
        ClusterNavigation.setEnabled(ctx, false)
        DriverProfiles.reset(ctx, scott)
        assertFalse(TurnAlertStore.get(ctx).current.enabled)
        assertTrue(ClusterNavigation.isEnabled(ctx))
    }

    @Test
    fun forgettingAPhoneDeletesItsProfile() {
        DriverProfiles.onPhone(ctx, scott, "Scott's iPhone")
        DriverProfiles.onPhone(ctx, guest, "Guest")
        turnAlert().setEnabled(true)
        DriverProfiles.forget(ctx, guest)
        assertNull("the guest was in use: no one is now", DriverProfiles.active(ctx))
        assertEquals(listOf(scott), view.all.map { it.mac })
        DriverProfiles.onPhone(ctx, guest, "Guest")
        assertFalse("paired again: a new profile", turnAlert().current.enabled)
    }

    @Test
    fun aProfileFromBeforeTheListIsKept() {
        // v27-v30 kept only the active driver.
        seedShared("carlink_driver_profiles", "active_mac" to scott, "active_name" to "Scott's iPhone", "legacy_owner" to scott)
        assertEquals(listOf(DriverProfiles.Profile(scott, "Scott's iPhone")), view.all)
        DriverProfiles.onPhone(ctx, guest, "Guest")
        assertEquals(listOf(guest, scott), view.all.map { it.mac })
        assertEquals("Scott's iPhone", view.all.last().name)
    }
}
