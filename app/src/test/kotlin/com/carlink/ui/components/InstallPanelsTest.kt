package com.carlink.ui.components

import com.carlink.ocbm.InstallMode
import com.carlink.ocbm.OneFileInstall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-file install's welcome card, in Scott's words (4 Nov). */
class InstallPanelsTest {
    @Test
    fun `the welcome card names the installed version, the failsafe and where to remove it`() {
        val text = trialText(OneFileInstall(InstallMode.TRIAL, "1.0.1"))
        assertTrue(text.startsWith("OCBM 1.0.1 has been installed, and your adapter passed its self-test."))
        assertTrue(text.contains("Carlink Settings > About"))
        assertTrue(text.endsWith("should you need it."))
        assertFalse("the trial no longer finishes on its own", text.contains("drive ends"))
        assertTrue(trialText(OneFileInstall(InstallMode.TRIAL)).startsWith("OCBM has been installed"))
    }
}
