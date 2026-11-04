package com.carlink.ui.components

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.carlink.CarlinkManager
import com.carlink.logging.logInfo
import com.carlink.ocbm.GoBackResult
import com.carlink.ocbm.InstallMode
import com.carlink.ocbm.OneFileInstall

// The OCBM one-file install on the connecting screen (handoff 10-28, plan page's mockup): the trial
// with its way back to stock CarPlay, the restart after going back, and the finish, said once.

/**
 * The connecting screen's panel for the adapter's one-file install, or [otherwise] when there is
 * nothing to say. While the trial keeps stock CarPlay the panel offers the way back. Being on screen
 * confirms nothing: the install finishes only when the driver agrees to for an update (Scott, 1 Nov).
 */
@Composable
internal fun InstallPanelOr(
    carlinkManager: CarlinkManager,
    otherwise: @Composable () -> Unit,
) {
    val session = carlinkManager.oneFileInstall
    val install by session.install.collectAsState()
    val goBack by session.goBack.collectAsState()
    val context = LocalContext.current
    // Once per install ([OneFileInstall.recordKey]), not once per connection: an update restarts the adapter and
    // Carlink reconnects to the same trial (bench 11-03: the welcome, with its "take the USB stick out" text, came
    // back after an update).
    var trialSeen by remember(install?.recordKey) { mutableStateOf(FinishNotice.welcomed(context, install?.recordKey)) }
    var finishSeen by remember(install?.recordKey) { mutableStateOf(FinishNotice.seen(context, install?.recordKey)) }
    val current = install
    when {
        goBack == GoBackResult.GOING ->
            InstallPanel("Restarting on stock CarPlay", "About 30 seconds. The adapter comes back on its Carlinkit firmware.")
        current != null && current.mode.stockKept && !trialSeen ->
            TrialPanel(
                current,
                onContinue = {
                    FinishNotice.markWelcomed(context, current.recordKey)
                    trialSeen = true
                },
                onGoBack = {
                    FinishNotice.clearWelcomed(context, current.recordKey)
                    session.goBackToStock()
                },
            )
        current != null && current.mode == InstallMode.FINAL && !finishSeen -> {
            val acknowledge = {
                FinishNotice.markSeen(context, current.recordKey)
                FinishNotice.clearWelcomed(context, current.recordKey)
                finishSeen = true
            }
            InstallPanel(
                title = "Conversion finished",
                body = "The Carlinkit files were removed after your last drive. The adapter runs OCBM only.",
                primary = "OK" to acknowledge,
            )
        }
        else -> otherwise()
    }
}

/** The trial: stock CarPlay stays as the fallback until the driver agrees to remove it, and the way back (asked once more in a dialog). */
@Composable
private fun TrialPanel(
    install: OneFileInstall,
    onContinue: () -> Unit,
    onGoBack: () -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    InstallPanel(
        title = "Welcome to Carlink",
        body = trialText(install),
        primary = "Continue" to onContinue,
        wayBack = { asking = true },
    )
    if (asking) {
        GoBackToStockDialog(
            onGoBack = {
                asking = false
                onGoBack()
            },
            onDismiss = { asking = false },
        )
    }
}

/** "Go back to stock CarPlay?", built into every way back so a stray tap cannot undo the conversion. */
@Composable
internal fun GoBackToStockDialog(
    onGoBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    CarlinkDialog(
        title = "Go back to stock CarPlay?",
        text =
            "The adapter restarts on Carlinkit firmware 2025.10.15.1127. HiCar, Android Mirror and the web UI " +
                "stay removed. You can convert again later.",
        confirmLabel = "Go back",
        onConfirm = onGoBack,
        onDismiss = onDismiss,
        icon = Icons.Default.SettingsBackupRestore,
        weight = DialogWeight.DANGER,
    )
}

/**
 * "Remove stock CarPlay?" (Scott, 2 Nov): finishing deletes the copy of the Carlinkit firmware on the adapter, and the
 * USB stick's backup is then the way back. Asked once more, as the way back is.
 */
@Composable
internal fun RemoveStockDialog(
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    CarlinkDialog(
        title = "Remove stock CarPlay?",
        text =
            "OCBM passed its self-test and has carried CarPlay. Removing stock CarPlay deletes the copy of the Carlinkit " +
                "firmware on the adapter, which frees the room for updates. After that, the backup on your USB stick is the " +
                "way back to stock. It's removed when this drive ends.",
        confirmLabel = "Remove",
        onConfirm = onRemove,
        onDismiss = onDismiss,
        icon = Icons.Default.DeleteSweep,
        weight = DialogWeight.DANGER,
    )
}

/**
 * The welcome card's text for a trial, in Scott's words (4 Nov): the install and its self-test, stock kept as the failsafe,
 * where removing it is, and the stick to keep.
 */
internal fun trialText(install: OneFileInstall): String =
    "OCBM${install.version?.let { " $it" }.orEmpty()} has been installed, and your adapter passed its self-test. " +
        "Stock firmware continues to reside on the adapter as a failsafe. To make OCBM firmware permanent and allow for " +
        "future firmware updates, remove the factory firmware from the adapter by going to Carlink Settings > About. " +
        "You may now proceed to take the USB stick out of the adapter and keep it secure. It holds the backup and " +
        "restore to factory image should you need it."

/** "OCBM 1.0.0 · trial": the install's version (when read) and mode, as the panels and Settings show it. */
internal fun installLabel(install: OneFileInstall): String = "OCBM${install.version?.let { " $it" }.orEmpty()} · ${install.mode.wire}"

@Composable
private fun InstallPanel(
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>? = null,
    wayBack: (() -> Unit)? = null,
) {
    val actions =
        listOfNotNull(
            primary?.let { (label, onClick) -> PanelAction(label, onClick = onClick) },
            wayBack?.let { PanelAction("Go back to stock CarPlay", PanelActionLook.DANGER, it) },
        )
    OverlayPanel(PanelContent(title, body, actions = actions))
}

/** Whether "Conversion finished" was acknowledged on this adapter ([serial]), so it is said once. */
private object FinishNotice {
    private const val PREFS = "carlink_one_file_install"
    private const val KEY_SEEN = "finish_seen"
    private const val KEY_WELCOMED = "trial_welcomed"

    fun seen(
        context: Context,
        serial: String?,
    ): Boolean = serial != null && serial in prefs(context).getStringSet(KEY_SEEN, emptySet()).orEmpty()

    fun markSeen(
        context: Context,
        serial: String,
    ) {
        val p = prefs(context)
        p.edit().putStringSet(KEY_SEEN, p.getStringSet(KEY_SEEN, emptySet()).orEmpty() + serial).apply()
        logInfo("[INSTALL] conversion-finished notice acknowledged", tag = "UI")
    }

    /** Whether the trial's welcome was dismissed for this adapter: cleared when its trial ends, so a new install welcomes again. */
    fun welcomed(
        context: Context,
        serial: String?,
    ): Boolean = serial != null && serial in prefs(context).getStringSet(KEY_WELCOMED, emptySet()).orEmpty()

    fun markWelcomed(
        context: Context,
        serial: String,
    ) {
        val p = prefs(context)
        p.edit().putStringSet(KEY_WELCOMED, p.getStringSet(KEY_WELCOMED, emptySet()).orEmpty() + serial).apply()
    }

    fun clearWelcomed(
        context: Context,
        serial: String,
    ) {
        val p = prefs(context)
        p.edit().putStringSet(KEY_WELCOMED, p.getStringSet(KEY_WELCOMED, emptySet()).orEmpty() - serial).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
