package com.carlink

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPromptTest {
    /** A launcher that records what it was asked for instead of showing a dialog. */
    private class Recorder : ActivityResultLauncher<String>() {
        val asked = mutableListOf<String>()

        override fun launch(
            input: String,
            options: ActivityOptionsCompat?,
        ) {
            asked += input
        }

        override fun unregister() = Unit

        override val contract: ActivityResultContract<String, *> = ActivityResultContracts.RequestPermission()
    }

    /** The 11 Nov bench: the cluster's bring-back must wait while a dialog waits for its answer. */
    @Test
    fun `a request is open from the ask until its answer`() {
        val launcher = Recorder()
        PermissionPrompt.ask(launcher, "android.permission.ACCESS_FINE_LOCATION")
        assertTrue(PermissionPrompt.isOpen)
        assertTrue(PermissionPrompt.open.value)
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), launcher.asked)
        PermissionPrompt.answered()
        assertFalse(PermissionPrompt.isOpen)
    }
}
