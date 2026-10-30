package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The update's pure rules: OCBM version order, what Settings offers, and the adapter's result lines. */
class AdapterUpdateTest {
    private val pkg = UpdatePackage(version = "1.1.0", fromMin = "1.0.0", needKb = 800)

    private fun trial(version: String? = "1.0.0") = OneFileInstall(InstallMode.TRIAL, version)

    // ---- version order, as ocbm_update.sh `newer` has it --------------------------------------------

    @Test
    fun `versions compare as numbers, part by part`() {
        assertTrue(OcbmVersion.newer("1.1.0", "1.0.0"))
        assertTrue(OcbmVersion.newer("1.10.0", "1.9.0"))
        assertTrue(OcbmVersion.newer("2.0", "1.9.9"))
        assertFalse(OcbmVersion.newer("1.0.0", "1.0.0"))
        assertFalse(OcbmVersion.newer("1.0.0", "1.0.1"))
    }

    @Test
    fun `a pre-release is newer than the version before it, and older than its own release`() {
        // The same three cases tools/ocbm_update_test.sh checks on the adapter's side (enigy/ocbm f8f18c8).
        assertTrue(OcbmVersion.newer("1.1.0-beta.1", "1.0.0"))
        assertFalse(OcbmVersion.newer("1.1.0-beta.2", "1.1.0"))
        assertTrue(OcbmVersion.newer("1.1.0", "1.1.0-beta.2"))
        assertTrue(OcbmVersion.newer("1.1.0-beta.2", "1.1.0-beta.1"))
    }

    // ---- the offer -----------------------------------------------------------------------------

    @Test
    fun `a newer package that fits is offered in place`() {
        assertEquals(UpdateOffer.InPlace(pkg), updateOffer(trial(), takesUpdates = true, pkg = pkg, freeKb = 1252))
        assertEquals(UpdateOffer.InPlace(pkg), updateOffer(OneFileInstall(InstallMode.FINAL, "1.0.0"), true, pkg, 3900))
    }

    @Test
    fun `an adapter that did not say its free flash is offered in place, and checks for itself`() {
        assertEquals(UpdateOffer.InPlace(pkg), updateOffer(trial(), true, pkg, freeKb = null))
    }

    @Test
    fun `nothing is offered without a package, a newer one, a known version or CAP_UPDATE`() {
        assertEquals(UpdateOffer.None, updateOffer(trial(), true, pkg = null, freeKb = 1252))
        assertEquals(UpdateOffer.None, updateOffer(trial("1.1.0"), true, pkg, 1252))
        assertEquals(UpdateOffer.None, updateOffer(trial("1.2.0"), true, pkg, 1252))
        assertEquals(UpdateOffer.None, updateOffer(trial(version = null), true, pkg, 1252))
        assertEquals(UpdateOffer.None, updateOffer(null, true, pkg, 1252))
        assertEquals(UpdateOffer.None, updateOffer(trial(), takesUpdates = false, pkg = pkg, freeKb = 1252))
    }

    @Test
    fun `a trial adapter without room takes the round trip, and is told why`() {
        val offer = updateOffer(trial(), true, pkg, freeKb = 700)
        assertTrue(offer is UpdateOffer.RoundTrip)
        assertEquals("It needs about 800 KB of flash, and this adapter has 700 KB free.", (offer as UpdateOffer.RoundTrip).why)
    }

    @Test
    fun `a trial adapter older than the package's FROM_MIN takes the round trip too`() {
        val offer = updateOffer(trial("0.9.0"), true, pkg, 1252)
        assertEquals(UpdateOffer.RoundTrip(pkg, "It updates OCBM 1.0.0 or newer, and this adapter runs 0.9.0."), offer)
    }

    @Test
    fun `without the stick conversion in the build, a trial adapter that can't take it is told so instead`() {
        assertTrue(updateOffer(trial(), true, pkg, freeKb = 700, canConvert = false) is UpdateOffer.CannotTake)
    }

    @Test
    fun `a final adapter that can't take it has no round trip`() {
        val offer = updateOffer(OneFileInstall(InstallMode.FINAL, "1.0.0"), true, pkg, freeKb = 500)
        assertTrue(offer is UpdateOffer.CannotTake)
    }

    @Test
    fun `a confirmed install waits for the drive to end, fitting or not`() {
        assertEquals(UpdateOffer.AfterThisDrive(pkg), updateOffer(OneFileInstall(InstallMode.CONFIRMED, "1.0.0"), true, pkg, 3900))
        assertEquals(UpdateOffer.AfterThisDrive(pkg), updateOffer(OneFileInstall(InstallMode.CONFIRMED, "1.0.0"), true, pkg, 100))
    }

    // ---- the adapter's result lines (enigy/ocbm f8f18c8: ocbm_update.sh and ocbm_dispatch.sh) ---------

    @Test
    fun `a commit names the version it moved to`() {
        val r = UpdateResult.parse("committed 1.1.0 ocbm new (from 1.0.0 ocbm old)")
        assertEquals(UpdateResult(UpdateState.COMMITTED, "1.1.0", "", null), r)
        assertEquals("1.0.1", UpdateResult.parse("committed 1.0.1 ocbm bump (from 1.0.0 ocbm old): no files changed")?.version)
    }

    @Test
    fun `a roll-back gives its reason and the version kept, parentheses in the reason and all`() {
        val r = UpdateResult.parse("rolled_back 1.1.0 ocbm new: its start-up self-test failed (selftest: FAIL ocbmd) (kept 1.0.0 ocbm old)")
        assertEquals(UpdateState.ROLLED_BACK, r?.state)
        assertEquals("1.1.0", r?.version)
        assertEquals("its start-up self-test failed (selftest: FAIL ocbmd)", r?.reason)
        assertEquals("1.0.0", r?.kept)
        val hang = UpdateResult.parse("rolled_back 1.1.0 ocbm new: OCBM 1.1.0 started 2 times in a row without passing its start-up self-test (kept 1.0.0 ocbm old)")
        assertEquals("OCBM 1.1.0 started 2 times in a row without passing its start-up self-test", hang?.reason)
    }

    @Test
    fun `a refusal gives the adapter's reason, also when the package had no version`() {
        val why =
            "Not enough flash: 300 KB free, and the update needs about 9 KB plus 384 KB spare. The files it replaces stay until it " +
                "passes its self-test, so it can always be undone."
        val room = UpdateResult.parse("refused 1.1.0 ocbm new: $why")
        assertEquals(UpdateState.REFUSED, room?.state)
        assertEquals("1.1.0", room?.version)
        assertEquals(why, room?.reason)
        val damaged = UpdateResult.parse("refused ?: The update package could not be unpacked: it is damaged.")
        assertNull(damaged?.version)
        assertEquals("The update package could not be unpacked: it is damaged.", damaged?.reason)
    }

    @Test
    fun `an empty or unknown line is no result`() {
        assertNull(UpdateResult.parse(""))
        assertNull(UpdateResult.parse("garbage here"))
        assertEquals(UpdateState.NONE, UpdateState.of("none"))
        assertEquals(UpdateState.NONE, UpdateState.of(null))
        assertEquals(UpdateState.TRIAL, UpdateState.of("trial"))
    }
}
