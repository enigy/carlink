package com.carlink.ocbm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * M2 (handoff notes, the adapter side): an attach whose SUBSCRIBE config is byte for byte the running session's keeps the
 * phone's CarPlay session; any other byte restarts it, about a cold start. So the YAML must come out the same from the
 * same settings every time: no clock, no randomness, no map order, every list in the order given. A change to this pin
 * is a deliberate one, and costs each Wi-Fi adapter one session restart after the update.
 */
class SubscribeYamlStabilityTest {
    /** Every optional part CarlinkManager.vehicleConfigSpec fills, as on the truck's panel. */
    private fun spec() =
        VehicleConfigSpec(
            enablesHevc = true,
            name = "carlink",
            width = 2400,
            height = 960,
            maxFps = 60,
            safeOriginX = 0,
            safeOriginY = 0,
            safeWidth = 2400,
            safeHeight = 960,
            dpi = 160,
            diagonalInches = 13.4,
            oemIconImages = listOf(OemIcon.Image(120, 120, "aWNvbjEyMA=="), OemIcon.Image(180, 180, "aWNvbjE4MA==")),
            oemIconLabel = "Carlink",
            rightHandDrive = false,
            viewArea2 = PixelRect(0, 0, 1600, 960),
            viewAreaAnimMs = SplitViewArea.TRANSITION_MS,
            metadataTier = "extended",
            metadataSkip = VehicleConfigSpec.LANES_ONLY_SKIP,
            location = listOf("GPGGA", "GPRMC", "PASCD"),
            vehicleStatus = listOf("range", "outsideTemperature", "rangeWarning"),
            engineTypes = listOf("gasoline"),
        )

    @Test
    fun `the same settings give the same bytes every time`() {
        val a = VehicleConfigYaml.renderBytes(spec())
        Thread.sleep(5)
        val b = VehicleConfigYaml.renderBytes(spec())
        assertArrayEquals(a, b)
    }

    @Test
    fun `a full config's bytes are pinned`() {
        val sha = MessageDigest.getInstance("SHA-256").digest(VehicleConfigYaml.renderBytes(spec())).toHex()
        assertEquals(PINNED, sha)
    }

    private companion object {
        const val PINNED = "eb01826b3d601433688baaf30a937586ad203dd0ba41c2b4dda3b6bf01a07496"
    }
}
