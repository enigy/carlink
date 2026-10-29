package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The OCBM one-file install as the app reads it, and when it may confirm the trial (handoff 10-28). */
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
    fun `only a trial, with the way back on screen, from a build that sends it, once`() {
        assertTrue(mayConfirmInstall(InstallMode.TRIAL, wayBackShown = true, buildAllows = true, alreadyTried = false))
        assertFalse("no way back on screen", mayConfirmInstall(InstallMode.TRIAL, wayBackShown = false, buildAllows = true, alreadyTried = false))
        assertFalse("this build holds it back", mayConfirmInstall(InstallMode.TRIAL, wayBackShown = true, buildAllows = false, alreadyTried = false))
        assertFalse("once a session", mayConfirmInstall(InstallMode.TRIAL, wayBackShown = true, buildAllows = true, alreadyTried = true))
        assertFalse("already confirmed", mayConfirmInstall(InstallMode.CONFIRMED, wayBackShown = true, buildAllows = true, alreadyTried = false))
        assertFalse("finished", mayConfirmInstall(InstallMode.FINAL, wayBackShown = true, buildAllows = true, alreadyTried = false))
        assertFalse("not a one-file install", mayConfirmInstall(null, wayBackShown = true, buildAllows = true, alreadyTried = false))
    }
}
