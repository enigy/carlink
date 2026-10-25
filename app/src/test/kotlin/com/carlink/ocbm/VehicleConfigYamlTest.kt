package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden test for the pushed vehicle config.
 *
 * This exists because of a specific, recorded failure: a hand-typed fixture once certified a broken
 * emitter whose missing trailing newline glued two keys together, so serde rejected the whole document
 * and the box silently fell back to 1920x720 with HEVC off. The byte-for-byte comparison below is the
 * mechanism that would have caught it, and the per-line newline assertion pins that exact class of bug.
 *
 * **If you change the emitter, change this literal in the same commit.**
 */
class VehicleConfigYamlTest {
    private val expected =
        """
name: "CarLink GM 2400x960"
version: 1
wireless: true
hot_handover: false
pairing: just_works
rightHandDrive: false
nightMode: false
displayPanelsConfig:
  mainDisplayPanel:
    displayPanelID: DisplayPanel.Main
    pixelDimensions:
      width: 2400
      height: 960
  altDisplayPanels: []
videoStreamsConfig:
  mainVideoStream:
    videoStreamID: VideoStream.Main
    pixelDimensions:
      width: 2400
      height: 960
    maxFPS: 60
    viewAreas:
    - viewArea:
        originX: 0
        originY: 0
        width: 2400
        height: 960
      safeArea:
        originX: 0
        originY: 0
        width: 2400
        height: 960
      drawUIOutsideSafeArea: false
    hidConfig:
      dPadSupport: false
      knobSupport: false
      knobSupportsHomeAndBackButton: false
      knobSupportsNudge: false
      mediaButtonsSupport: true
      telephonyButtonsSupport: true
      touchpadSupport: false
      touchpadButtonsSupport: false
      touchScreenMode: High Fidelty
      touchScreenSupportsCancel: true
      touchScreenSupportsMultiTouch: true
      steeringWheelSupport: false
    primaryInput: Touchpad
  altVideoStreams: []
accessoryConfig:
  enablesMainBufferedAudio: false
  enablesHEVC: true
  enablesUIAppearance: true
  enablesMapAppearance: true
  enablesCornerMasks: false
  enablesVideoPlayback: true
  enablesViewAreas: false
  enablesEnhancedSiri: false
  enablesFocusTransfer: false
  enablesUIContext: false
  enablesUISync: false
  enablesFileTransfer: false
  enablesLogTransfer: false
  enablesVehicleDataProtocol: false
  enablesDCX: false
  appDrivenSetup: false
limitedUIConfig:
  softKeyboard: true
  softPhoneKeypad: true
  nonMusicLists: true
  musicLists: true
  japanMaps: false
  longAlerts: true
audio:
  wired:
    preset: wired_pcm
  wireless:
    preset: wireless_8
metadata:
  tier: proven
""".trimStart('\n')

    @Test
    fun `the default document renders byte for byte`() {
        assertEquals(expected, VehicleConfigYaml.render(VehicleConfigSpec()))
    }

    /** The exact 2026-08-11 failure mode: one line without its newline glues two keys together. */
    @Test
    fun `every line ends with a newline, including the last`() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec())
        assertTrue("document must end with a newline", doc.endsWith("\n"))
        assertFalse("no blank lines — serde tolerates them but the fixture does not", doc.contains("\n\n"))
        assertFalse("no trailing whitespace", doc.lines().any { it != it.trimEnd() })
    }

    @Test
    fun `the target geometry appears in all four places`() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec())
        // panel, stream, viewArea, safeArea — a mismatch between any two is a silent letterbox
        assertEquals(4, Regex("width: 2400").findAll(doc).count())
        assertEquals(4, Regex("height: 960").findAll(doc).count())
    }

    @Test
    fun `Apple's own misspelling is preserved`() {
        // "High Fidelty" is Apple's typo. Correcting it costs the touch bits.
        assertTrue(VehicleConfigYaml.render(VehicleConfigSpec()).contains("touchScreenMode: High Fidelty"))
    }

    @Test
    fun `every unused HID device stays unadvertised`() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec())
        for (k in listOf(
            "dPadSupport",
            "knobSupport",
            "knobSupportsHomeAndBackButton",
            "knobSupportsNudge",
            "touchpadSupport",
            "touchpadButtonsSupport",
            "steeringWheelSupport",
        )) {
            assertTrue(
                "$k must be false — advertising an unserved HID has broken reconnect before",
                doc.contains("$k: false"),
            )
        }
        // The two input devices we DO serve: media keys, and the telephony buttons behind Answer / Hang up.
        assertTrue(doc.contains("mediaButtonsSupport: true"))
        assertTrue(doc.contains("telephonyButtonsSupport: true"))
    }

    /** Settings › Display › Layout: the Dock resize animates for 1.5 s instead of the box's 3 s. */
    @Test
    fun `the view-area animation time is a top-level key, inside the box's range, and absent by default`() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec(viewArea2 = PixelRect(0, 0, 1680, 960), viewAreaAnimMs = 1500))
        assertTrue(doc, doc.lines().contains("view_area_anim_ms: 1500"))
        assertFalse(VehicleConfigYaml.render(VehicleConfigSpec()).contains("view_area_anim_ms"))
        for (bad in listOf(999, 10_001, 0)) {
            var threw = false
            try {
                VehicleConfigSpec(viewAreaAnimMs = bad)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("$bad ms must be refused", threw)
        }
    }

    @Test
    fun `a resolution change moves geometry everywhere at once`() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec(width = 1920, height = 720))
        assertEquals(4, Regex("width: 1920").findAll(doc).count())
        assertEquals(4, Regex("height: 720").findAll(doc).count())
    }

    @Test
    fun `a safe area inset from a display cutout is emitted independently of the view area`() {
        val doc =
            VehicleConfigYaml.render(
                VehicleConfigSpec(
                    safeOriginX = 40,
                    safeOriginY = 10,
                    safeWidth = 2320,
                    safeHeight = 940,
                    drawUIOutsideSafeArea = true,
                ),
            )
        assertTrue(doc.contains("        originX: 40"))
        assertTrue(doc.contains("        width: 2320"))
        assertTrue(doc.contains("      drawUIOutsideSafeArea: true"))
        // ...while the view area still covers the whole panel.
        assertTrue(doc.contains("        width: 2400"))
    }

    @Test
    fun `an illegal frame rate is refused at construction rather than on the wire`() {
        // 24 was removed box-side in 2026-07; the box silently ignores anything but 30/60, which would
        // present as "my config didn't apply" rather than as an error.
        for (bad in listOf(24, 25, 0, 120)) {
            var threw = false
            try {
                VehicleConfigSpec(maxFps = bad)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("maxFPS=$bad must be rejected", threw)
        }
    }

    @Test
    fun `enablesHEVC follows the decoder probe so a codec the head unit cannot decode is never offered`() {
        // Default (probe found an HEVC decoder): the document offers HEVC.
        assertTrue(VehicleConfigYaml.render(VehicleConfigSpec()).contains("  enablesHEVC: true\n"))
        // No HEVC decoder at the negotiated geometry: the ONLY change is that one line — the box then
        // omits hevcInfo and iOS encodes H.264, which VideoRenderer decodes.
        val doc = VehicleConfigYaml.render(VehicleConfigSpec(enablesHevc = false))
        assertTrue(doc.contains("  enablesHEVC: false\n"))
        assertFalse(doc.contains("enablesHEVC: true"))
        assertEquals(1, doc.lines().count { it.contains("enablesHEVC") })
        assertEquals(
            VehicleConfigYaml.render(VehicleConfigSpec()).replace("enablesHEVC: true", "enablesHEVC: false"),
            doc,
        )
    }

    @Test
    fun `the refuted metadata tiers cannot be selected by accident`() {
        var threw = false
        try {
            VehicleConfigSpec(metadataTier = "rx-only")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("rx-only is a refuted dead end the box rejects", threw)
        // `all` is legal to express but device-refuted; it stays constructible so the choice is explicit.
        assertEquals("all", VehicleConfigSpec(metadataTier = "all").metadataTier)
    }

    @Test
    fun `a name containing a quote cannot tear the document`() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec(name = """Owner's "Roadster" \ EV"""))
        assertTrue(doc.startsWith("""name: "Owner's \"Roadster\" \\ EV"""" + "\n"))
        assertEquals(1, doc.lines().count { it.startsWith("name:") })
    }

    // ---- oemIconConfig ---------------------------------------------------------------------------

    @Test
    fun `oemIconConfig is omitted entirely when there are no images`() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec())
        assertFalse("an empty section is a different statement from none", doc.contains("oemIconConfig"))
    }

    @Test
    fun `oemIconConfig emits Apple's three sizes in the box's key order`() {
        val doc =
            VehicleConfigYaml.render(
                VehicleConfigSpec(
                    oemIconImages =
                        listOf(
                            OemIcon.Image(120, 120, "AAAA"),
                            OemIcon.Image(180, 180, "BBBB"),
                            OemIcon.Image(256, 256, "CCCC"),
                        ),
                    oemIconLabel = "Carlink",
                ),
            )
        assertTrue(
            "emitted document did not contain the expected oemIconConfig block:\n$doc",
            doc.contains(
                """
                oemIconConfig:
                  images:
                    - width: 120
                      height: 120
                      imageBase64: "AAAA"
                    - width: 180
                      height: 180
                      imageBase64: "BBBB"
                    - width: 256
                      height: 256
                      imageBase64: "CCCC"
                  label: "Carlink"
                  visible: true
                """.trimIndent() + "\n",
            ),
        )
    }

    /** It sits between limitedUIConfig and audio, matching the box's struct order. */
    @Test
    fun `oemIconConfig is placed between limitedUIConfig and audio`() {
        val doc =
            VehicleConfigYaml.render(
                VehicleConfigSpec(oemIconImages = listOf(OemIcon.Image(120, 120, "AAAA"))),
            )
        val limited = doc.indexOf("limitedUIConfig:")
        val oem = doc.indexOf("oemIconConfig:")
        val audio = doc.indexOf("audio:")
        assertTrue("all three sections present", limited >= 0 && oem >= 0 && audio >= 0)
        assertTrue("limitedUIConfig must precede oemIconConfig", limited < oem)
        assertTrue("oemIconConfig must precede audio", oem < audio)
    }

    @Test
    fun `a label with quotes or control characters is escaped like every other string`() {
        val doc =
            VehicleConfigYaml.render(
                VehicleConfigSpec(
                    oemIconImages = listOf(OemIcon.Image(120, 120, "AAAA")),
                    oemIconLabel = "Owner\u0007s \"Car\"",
                ),
            )
        // The BEL is stripped (Cc is rejected at the YAML stream level), the quote is escaped.
        assertTrue("label not escaped as expected:\n$doc", doc.contains("""  label: "Owners \"Car\""""))
    }

    /** Settings → Display → Driver's seat: right puts CarPlay's dock on the right. */
    @Test
    fun `right-hand drive is emitted when chosen`() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec(rightHandDrive = true))
        assertTrue("rightHandDrive line missing:\n$doc", doc.lines().contains("rightHandDrive: true"))
        assertFalse(doc.contains("rightHandDrive: false"))
    }

    /** Settings › Cluster › Ask iPhone for lanes: the box's flow-sequence skip list, after the tier. */
    @Test
    fun `the lanes-only request ends the document with the tier and its skip list`() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec(metadataTier = "extended", metadataSkip = VehicleConfigSpec.LANES_ONLY_SKIP))
        assertTrue(
            doc,
            doc.endsWith(
                "metadata:\n  tier: extended\n  skip: [communications, call_history, power, app_discovery, destination, device_info]\n",
            ),
        )
        assertFalse("lanes ride route guidance; skipping it is an unrecoverable reject", "route_guidance" in VehicleConfigSpec.LANES_ONLY_SKIP)
        assertFalse("the default document has no skip key", VehicleConfigYaml.render(VehicleConfigSpec()).contains("skip:"))
    }

    // ---- split layout: viewAreas[1] -------------------------------------------------------------

    /** Settings › Display › Layout › Media on right at 30 %: the device-proven second area, starting split. */
    @Test
    fun `the split layout adds a second view area with initial and arms enablesViewAreas`() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec(viewArea2 = PixelRect(0, 0, 1680, 960)))
        // From the first entry's last key to hidConfig: the whole second entry, at the first's indent.
        val entry =
            listOf(
                "      drawUIOutsideSafeArea: false",
                "    - viewArea:",
                "        originX: 0",
                "        originY: 0",
                "        width: 1680",
                "        height: 960",
                "      safeArea:",
                "        originX: 0",
                "        originY: 0",
                "        width: 1680",
                "        height: 960",
                "      drawUIOutsideSafeArea: false",
                "      initial: true",
                "    hidConfig:",
            ).joinToString("\n", postfix = "\n")
        assertTrue("second viewAreas entry missing or misshapen:\n$doc", doc.contains(entry))
        assertTrue(doc.contains("  enablesViewAreas: true\n"))
        assertFalse(doc.contains("enablesViewAreas: false"))
        // The first entry is still the whole panel.
        assertEquals(1, Regex("        originX: 0\n        originY: 0\n        width: 2400\n        height: 960\n      safeArea:").findAll(doc).count())
    }

    @Test
    fun `without a second area the document is the pinned default, enablesViewAreas off`() {
        assertEquals(expected, VehicleConfigYaml.render(VehicleConfigSpec(viewArea2 = null)))
        assertFalse(expected.contains("initial:"))
    }

    @Test
    fun `a second area iOS would tear the session down for is refused at construction`() {
        for (bad in listOf(PixelRect(721, 0, 1680, 960), PixelRect(0, 0, 1681, 960), PixelRect(800, 0, 1680, 960), PixelRect(0, 0, 0, 960))) {
            var threw = false
            try {
                VehicleConfigSpec(viewArea2 = bad)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("$bad must be rejected", threw)
        }
        // A landscape area below the 800x480 floor: a black lockout rather than a teardown, refused all
        // the same. (780x960 would be a PORTRAIT area, legal against its own 480x800 floor.)
        var threw = false
        try {
            VehicleConfigSpec(viewArea2 = PixelRect(0, 0, 790, 480))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `a skip name that could break the flow sequence is refused`() {
        var threw = false
        try {
            VehicleConfigSpec(metadataSkip = listOf("power], tier: all"))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
