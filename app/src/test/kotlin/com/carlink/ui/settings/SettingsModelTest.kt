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
    fun aPhoneTheAdapterNoLongerHasIsNotConnected() {
        // 2 Nov bench: a wireless phone dropped, the adapter waited for one, and the card still read
        // "iPhone · Wired CarPlay" with the USB port "In use by the connected phone".
        val gone = PhoneSnapshot(PhoneType.CARPLAY, wifi = -1, btMac = work.btMac, present = false)
        assertNull(SettingsModel.connectedPhone(gone, listOf(work)))
    }

    @Test
    fun aClearedIdentityNamesNoKnownPhone() {
        // 11-03 bench: through a switch the old phone stayed "Connected" after the adapter cleared its identity,
        // because the manager kept its MAC. With the MAC gone the card says only that a wireless phone is on it,
        // and no row in the list is the connected one.
        val between = PhoneSnapshot(PhoneType.CARPLAY_WIRELESS, wifi = 1, btMac = null)
        val phone = SettingsModel.connectedPhone(between, listOf(work, old))!!
        assertEquals("Wireless phone", phone.name)
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
    fun aTrialsFullFlashIsNotAFault() {
        // Bench 11-03: with stock kept the flash is full by design, so the rootfs bit is off on a healthy trial.
        val trial = BoxSnapshot(adapterConnected = true, health = Ocbm.BH_HCI_PRESENT or Ocbm.BH_WLAN_AP, healthKnown = true, stockKept = true)
        assertEquals("carlink · all systems OK", SettingsModel.adapterSummary("carlink", trial))
        assertEquals(true, SettingsModel.healthItems(trial).single { it.label == "Firmware" }.ok)
        val final = trial.copy(stockKept = false)
        assertEquals("once stock is gone the bit counts again", "carlink · check status", SettingsModel.adapterSummary("carlink", final))
        assertEquals(false, SettingsModel.healthItems(final).single { it.label == "Firmware" }.ok)
        assertEquals("a trial still needs its radios", "carlink · check status", SettingsModel.adapterSummary("carlink", trial.copy(health = Ocbm.BH_HCI_PRESENT)))
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
        assertEquals(
            "Fullscreen · Media on right · Chevrolet loading screen",
            SettingsModel.displaySummary(DisplayMode.FULLSCREEN_IMMERSIVE, BackgroundLogoConfig.CHEVROLET, PaneLayout.MEDIA_RIGHT),
        )
        assertEquals("Automatic mic · Standard buffer", SettingsModel.callsSummary(CallMicConfig.AUTOMATIC, CallBufferConfig.STANDARD))
        assertEquals("Car call path · Low buffer", SettingsModel.callsSummary(CallMicConfig.CAR_CALL_PATH, CallBufferConfig.LOW))
        assertEquals("Directions on", SettingsModel.clusterSummary(true))
        assertEquals("Directions off", SettingsModel.clusterSummary(false))
        assertEquals("Directions on · Lane guidance on", SettingsModel.clusterSummary(true, lanes = true))
        assertEquals("Fixes and logs", SettingsModel.troubleshootSummary(false))
        assertEquals("Fixes and logs · Recording drive log", SettingsModel.troubleshootSummary(true))
        // 2 Nov bench: "Recording drive log" with nothing written since the day before.
        assertEquals("Fixes and logs · Drive log not recording", SettingsModel.troubleshootSummary(true, driveLogTrouble = true))
        assertEquals("Fixes and logs", SettingsModel.troubleshootSummary(false, driveLogTrouble = true))
    }

    @Test
    fun deviceStatusSaysWhetherTheAdapterHoldsIt() {
        assertEquals("Last seen Oct 14, 7:58 AM", SettingsModel.deviceStatus(work))
        assertEquals("Not paired with the adapter · app history only", SettingsModel.deviceStatus(old))
        assertEquals("Not connected", SettingsModel.deviceStatus(work.copy(lastConnected = null)))
    }
}
