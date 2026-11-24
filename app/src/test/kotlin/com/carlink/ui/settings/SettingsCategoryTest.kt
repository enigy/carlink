package com.carlink.ui.settings

import com.carlink.ui.settings.SettingsCategory.ABOUT
import com.carlink.ui.settings.SettingsCategory.ADAPTER
import com.carlink.ui.settings.SettingsCategory.BRIDGE
import com.carlink.ui.settings.SettingsCategory.TROUBLESHOOT
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings list's order (owner, 24 Nov): Adapter and Bridge Config next to each other, About and Troubleshoot
 * the bottom two, with Bridge Config listed or not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsCategoryTest {
    @Test
    fun `the whole list, with Bridge Config listed and without it`() {
        assertEquals(
            listOf("Phones", "Display", "Cluster & HUD", "Calls", "Vehicle", "Adapter Config", "Bridge Config", "About", "Troubleshoot"),
            SettingsCategory.listed(bridgeOn = true).map { it.title },
        )
        assertEquals(
            listOf("Phones", "Display", "Cluster & HUD", "Calls", "Vehicle", "Adapter Config", "About", "Troubleshoot"),
            SettingsCategory.listed(bridgeOn = false).map { it.title },
        )
    }

    @Test
    fun `Bridge Config comes right under Adapter Config, where its switch is`() {
        val listed = SettingsCategory.listed(bridgeOn = true)
        assertEquals(listed.indexOf(ADAPTER) + 1, listed.indexOf(BRIDGE))
    }

    @Test
    fun `About, then Troubleshoot, are the bottom two, with Bridge Config listed or not`() {
        for (bridgeOn in listOf(true, false)) assertEquals(listOf(ABOUT, TROUBLESHOOT), SettingsCategory.listed(bridgeOn).takeLast(2))
    }

    @Test
    fun `Bridge Config, open when it stops being listed, gives way to Adapter`() {
        assertEquals(ADAPTER, SettingsCategory.fallback(BRIDGE, bridgeOn = false))
        assertEquals(BRIDGE, SettingsCategory.fallback(BRIDGE, bridgeOn = true))
        for (other in SettingsCategory.entries - BRIDGE) assertEquals(other, SettingsCategory.fallback(other, bridgeOn = false))
    }
}
