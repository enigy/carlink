package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The OCBM one-file install as the app reads it, and when it may confirm the trial (handoff 10-28; Scott, 1 Nov). */
class OneFileInstallTest {
    @Test
    fun `the adapter's three words are read and nothing else`() {
        assertEquals(InstallMode.TRIAL, InstallMode.of("trial"))
        assertEquals(InstallMode.CONFIRMED, InstallMode.of("confirmed"))
        assertEquals(InstallMode.FINAL, InstallMode.of("final"))
        assertNull("none: not a one-file install (the truck adapter)", InstallMode.of("none"))
        assertNull(InstallMode.of(""))
        assertNull(InstallMode.of("Trial"))
        assertNull(InstallMode.of(null))
    }

    @Test
    fun `stock is kept until the finish`() {
        assertTrue(InstallMode.TRIAL.stockKept)
        assertTrue(InstallMode.CONFIRMED.stockKept)
        assertFalse(InstallMode.FINAL.stockKept)
    }

    @Test
    fun `the installed version is its first word`() {
        assertEquals("1.0.0", OneFileInstall.versionOf("1.0.0 ocbm 153df7f\n"))
        assertEquals("1.0.0", OneFileInstall.versionOf("1.0.0"))
        assertNull(OneFileInstall.versionOf("  \n"))
    }

    @Test
    fun `the confirm goes only to a trial, from a build that sends it`() {
        // Scott, 1-2 Nov: showing the way back confirms nothing; the driver's Yes (Remove stock CarPlay, or an update
        // needing the room) is the only sender, in every build unless built with -Pcarlink.installConfirm=false.
        assertTrue(mayConfirmInstall(InstallMode.TRIAL, buildAllows = true))
        assertFalse("a build made not to send it", mayConfirmInstall(InstallMode.TRIAL, buildAllows = false))
        assertFalse("already confirmed", mayConfirmInstall(InstallMode.CONFIRMED, buildAllows = true))
        assertFalse("finished", mayConfirmInstall(InstallMode.FINAL, buildAllows = true))
        assertFalse("not a one-file install", mayConfirmInstall(null, buildAllows = true))
    }
}
