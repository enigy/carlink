package com.carlink.ui.components

import com.carlink.ocbm.UpdatePhase
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Kept panel's reason in plain words (Scott, 5 Nov), from the adapter's update_result. */
class UpdatePanelsTest {
    @Test
    fun `an update that never answered names both versions`() {
        // The dev adapter's broken-link bench, 5 Nov.
        val kept = UpdatePhase.Kept("1.0.2", "ocbmd heard the host for 121 s and never answered it", "1.0.3")
        assertEquals("The adapter on OCBM 1.0.3 didn't answer Carlink, so it put back OCBM 1.0.2.", keptText(kept))
    }

    @Test
    fun `the other known reasons read plainly too`() {
        assertEquals(
            "OCBM 1.0.3 failed the adapter's start-up self-test, so it put back OCBM 1.0.2.",
            keptText(UpdatePhase.Kept("1.0.2", "its start-up self-test failed (FAILED after 30 s: ocbmd is not running)", "1.0.3")),
        )
        assertEquals(
            "OCBM 1.0.3 didn't start properly twice in a row, so the adapter put back OCBM 1.0.2.",
            keptText(UpdatePhase.Kept("1.0.2", "OCBM 1.0.3 started 2 times in a row without passing its start-up self-test", "1.0.3")),
        )
        assertEquals(
            "Power was lost during the update, so the adapter put back OCBM 1.0.2.",
            keptText(UpdatePhase.Kept("1.0.2", "power was lost while the update was being placed", "1.0.3")),
        )
    }

    @Test
    fun `an unknown reason is shown as it came, and a missing version reads generically`() {
        assertEquals("/usr/sbin/ocbmd is missing.", keptText(UpdatePhase.Kept("1.0.2", "/usr/sbin/ocbmd is missing", "1.0.3")))
        assertEquals(
            "The update failed the adapter's start-up self-test, so it put back the version it had.",
            keptText(UpdatePhase.Kept("the version it had", "its start-up self-test failed")),
        )
    }
}
