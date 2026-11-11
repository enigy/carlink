package com.carlink

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import com.carlink.logging.logWarn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a runtime permission dialog Carlink asked for is waiting for its answer: from [ask] until the request's
 * callback calls [answered]. The system shows the dialog in Carlink's own task, so the cluster's bring-back
 * (MainActivity reordered to the front) buried it under MainActivity, unanswerable, and Android then refused every
 * later request ("Can request only one set of permissions at a time"): the first-launch location request, Mac bench
 * 11 Nov. MainActivity holds its cluster steps while a dialog is up, as it does for
 * [com.carlink.usb.UsbPermissionPrompt].
 */
object PermissionPrompt {
    private val state = MutableStateFlow(false)

    val open: StateFlow<Boolean> = state.asStateFlow()

    val isOpen: Boolean get() = state.value

    /** Launch [launcher] with [input], marked open until [answered]. A launch that throws is not left open. */
    fun <I> ask(
        launcher: ActivityResultLauncher<I>,
        input: I,
    ) {
        state.value = true
        runCatching { launcher.launch(input) }.onFailure {
            state.value = false
            logWarn("[PERMISSION] request failed to launch: ${it.message}", tag = "MAIN")
        }
    }

    /** The request's answer came back. */
    fun answered() {
        state.value = false
    }

    /**
     * Carlink's page in the system's app settings, for a request Android would refuse because an earlier dialog
     * still waits unanswered. False where the head unit has no such page.
     */
    fun openAppSettings(context: Context): Boolean {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            logWarn("[PERMISSION] no app settings page: ${e.message}", tag = "MAIN")
            false
        }
    }
}
