package com.carlink.ui.components

import com.carlink.ocbm.InstallMode
import com.carlink.ocbm.OneFileInstall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-file install's welcome card, as the trial is now (Scott, 1 Nov). */
class InstallPanelsTest {
    @Test
    fun `the welcome card says stock stays as a fallback, and Carlink asks before removing it`() {
        val text = trialText(OneFileInstall(InstallMode.TRIAL, "1.0.0"))
        assertTrue(text.startsWith("OCBM 1.0.0 · trial. Your adapter passed its self-test."))
        assertTrue(text.contains("Stock CarPlay stays on it as a fallback, and Carlink asks before removing it."))
        assertFalse("the trial no longer finishes on its own", text.contains("drive ends"))
        assertTrue(text.endsWith(STICK_OUT))
    }
}
