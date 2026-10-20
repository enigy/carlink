package com.carlink.ui.settings

import com.carlink.CarlinkManager
import com.carlink.ocbm.Ocbm
import com.carlink.protocol.PhoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The words the merged settings screen derives from live manager and box state. */
@RunWith(RobolectricTestRunner::class)
class SettingsModelTest {
    private val work = CarlinkManager.DeviceInfo(btMac = "AA:BB:CC:DD:EE:01", name = "Work iPhone", type = "CarPlay", lastConnected = "Oct 14, 7:58 AM")
    private val old = CarlinkManager.DeviceInfo(btMac = "AA:BB:CC:DD:EE:02", name = "Old iPhone", type = "CarPlay", bonded = false)

    @Test
    fun noPhoneTypeMeansNothingConnected() {
        assertNull(SettingsModel.connectedPhone(PhoneSnapshot(), listOf(work)))
    }

    @Test
    fun wirelessPhoneTakesItsKnownDeviceName() {
        val phone = SettingsModel.connectedPhone(PhoneSnapshot(PhoneType.CARPLAY, wifi = 1, btMac = work.btMac), listOf(work, old))!!
        assertEquals("Work iPhone", phone.name)
        assertEquals("Wireless CarPlay", phone.kind)
        assertTrue(phone.wireless)
        assertEquals(work, phone.device)
    }

    @Test
    fun anActiveTypeWithoutTheWifiFieldIsUsb() {
        val phone = SettingsModel.connectedPhone(PhoneSnapshot(PhoneType.CARPLAY, wifi = -1, btMac = work.btMac), listOf(work))!!
        assertEquals("iPhone", phone.name)
        assertEquals("Wired CarPlay", phone.kind)
        assertFalse(phone.wireless)
        assertNull(phone.device)
    }

    @Test
    fun wirelessPhoneWithAnUnknownMacStillReads() {
        val phone = SettingsModel.connectedPhone(PhoneSnapshot(PhoneType.CARPLAY_WIRELESS, wifi = -1, btMac = "00:00:00:00:00:00"), listOf(work))!!
        assertEquals("Wireless phone", phone.name)
        assertEquals("Wireless CarPlay", phone.kind)
    }

    @Test
    fun phonesSummaryNamesTheConnectedPhoneAndCountsKnownOnes() {
        val phone = SettingsModel.connectedPhone(PhoneSnapshot(PhoneType.CARPLAY, wifi = 1, btMac = work.btMac), listOf(work))
        assertEquals("Work iPhone connected · 2 known", SettingsModel.phonesSummary(phone, 2))
        assertEquals("No phone connected · 0 known", SettingsModel.phonesSummary(null, 0))
    }

    @Test
    fun healthIsUnknownUntilTheBoxReportsIt() {
        val items = SettingsModel.healthItems(BoxSnapshot(adapterConnected = true, healthKnown = false))
        assertEquals(listOf("Bluetooth", "Wi-Fi", "CarPlay", "Firmware"), items.map { it.label })
        assertTrue(items.all { it.ok == null })
    }

    @Test
    fun healthBitsLightTheirChips() {
        val idle = Ocbm.BH_HCI_PRESENT or Ocbm.BH_SSP or Ocbm.BH_WLAN_AP or Ocbm.BH_ROOTFS_OK
        val items = SettingsModel.healthItems(BoxSnapshot(adapterConnected = true, health = idle, healthKnown = true))
        assertEquals(listOf(true, true, false, true), items.map { it.ok })
        val streaming = SettingsModel.healthItems(BoxSnapshot(adapterConnected = true, health = idle or Ocbm.BH_AIRPLAYD, healthKnown = true))
        assertEquals(true, streaming[2].ok)
    }

    @Test
    fun adapterSummaryCoversEveryState() {
        val healthy = Ocbm.BH_HCI_PRESENT or Ocbm.BH_WLAN_AP or Ocbm.BH_ROOTFS_OK
        assertEquals("carlink · not connected", SettingsModel.adapterSummary("carlink", BoxSnapshot()))
        assertEquals("carlink · connected", SettingsModel.adapterSummary("carlink", BoxSnapshot(adapterConnected = true)))
        assertEquals(
            "carlink · all systems OK",
            SettingsModel.adapterSummary("carlink", BoxSnapshot(adapterConnected = true, health = healthy, healthKnown = true)),
        )
        assertEquals(
            "carlink · check status",
            SettingsModel.adapterSummary("carlink", BoxSnapshot(adapterConnected = true, health = Ocbm.BH_HCI_PRESENT, healthKnown = true)),
        )
    }

    @Test
    fun adapterModeReadsTheProjectionMode() {
        assertEquals("Not connected", SettingsModel.adapterMode(BoxSnapshot(projectionMode = Ocbm.PM_WIRELESS_CP)))
        assertEquals("Wireless CarPlay running", SettingsModel.adapterMode(BoxSnapshot(adapterConnected = true, projectionMode = Ocbm.PM_WIRELESS_CP)))
        assertEquals("Wired CarPlay running", SettingsModel.adapterMode(BoxSnapshot(adapterConnected = true, projectionMode = Ocbm.PM_WIRED_CP)))
        assertEquals("Waiting for a phone", SettingsModel.adapterMode(BoxSnapshot(adapterConnected = true)))
    }

    @Test
    fun preferenceSummariesUseTheChoiceWords() {
        assertEquals("Fullscreen · Chevrolet loading screen", SettingsModel.displaySummary(DisplayMode.FULLSCREEN_IMMERSIVE, BackgroundLogoConfig.CHEVROLET))
        assertEquals("Automatic mic · Standard buffer", SettingsModel.callsSummary(CallMicConfig.AUTOMATIC, CallBufferConfig.STANDARD))
        assertEquals("Car call path · Low buffer", SettingsModel.callsSummary(CallMicConfig.CAR_CALL_PATH, CallBufferConfig.LOW))
        assertEquals("Directions on", SettingsModel.clusterSummary(true))
        assertEquals("Directions off", SettingsModel.clusterSummary(false))
        assertEquals("Fixes and logs", SettingsModel.troubleshootSummary(false))
        assertEquals("Fixes and logs · Recording drive log", SettingsModel.troubleshootSummary(true))
    }

    @Test
    fun deviceStatusSaysWhetherTheAdapterHoldsIt() {
        assertEquals("Last seen Oct 14, 7:58 AM", SettingsModel.deviceStatus(work))
        assertEquals("Not paired with the adapter · app history only", SettingsModel.deviceStatus(old))
        assertEquals("Not connected", SettingsModel.deviceStatus(work.copy(lastConnected = null)))
    }
}
