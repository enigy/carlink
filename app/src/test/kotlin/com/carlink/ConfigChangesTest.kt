package com.carlink

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigChangesTest {
    @Test
    fun `bits are named in ActivityInfo's order, hidden ones too`() {
        assertEquals(listOf("mcc", "mnc"), ConfigChanges.names(ActivityInfo.CONFIG_MNC or ActivityInfo.CONFIG_MCC))
        assertEquals(listOf("fontWeightAdjustment"), ConfigChanges.names(ActivityInfo.CONFIG_FONT_WEIGHT_ADJUSTMENT))
        assertEquals(listOf("assetsPaths"), ConfigChanges.names(0x80000000.toInt()))
        assertEquals(listOf("windowConfiguration"), ConfigChanges.names(0x20000000))
        assertEquals("a bit with no name shows in hex", listOf("uiMode", "0x00010000"), ConfigChanges.names(ActivityInfo.CONFIG_UI_MODE or 0x10000))
        assertEquals(emptyList<String>(), ConfigChanges.names(0))
    }

    @Test
    fun `the line says what changed and what the manifest doesn't declare`() {
        val declared = ActivityInfo.CONFIG_UI_MODE or ActivityInfo.CONFIG_DENSITY
        assertEquals(
            "changed mcc, mnc, uiMode; not declared: mcc, mnc",
            ConfigChanges.describe(ActivityInfo.CONFIG_MCC or ActivityInfo.CONFIG_MNC or ActivityInfo.CONFIG_UI_MODE, declared),
        )
        assertEquals("changed uiMode; not declared: none", ConfigChanges.describe(ActivityInfo.CONFIG_UI_MODE, declared))
        assertEquals("changed assetsPaths; not declared: assetsPaths", ConfigChanges.describe(0x80000000.toInt(), declared))
        assertEquals(
            "no configuration difference (an overlay or assets change, or a recreate())",
            ConfigChanges.describe(0, declared),
        )
    }
}
