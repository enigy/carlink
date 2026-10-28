package com.carlink.ui.components

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * nothing to say. While the trial keeps stock CarPlay the panel offers the way back, and its being on
 * screen is what lets the trial be confirmed ([com.carlink.ocbm.InstallSession.wayBackShown]).
 */
@Composable
internal fun InstallPanelOr(
    carlinkManager: CarlinkManager,
    otherwise: @Composable () -> Unit,
) {
    val session = carlinkManager.oneFileInstall
    val install by session.install.collectAsState()
    val goBack by session.goBack.collectAsState()
    var trialSeen by remember(carlinkManager, install?.mode) { mutableStateOf(false) }
    val context = LocalContext.current
    var finishSeen by remember(install?.serial) { mutableStateOf(FinishNotice.seen(context, install?.serial)) }
    val current = install
    when {
        goBack == GoBackResult.GOING ->
            InstallPanel("Restarting on stock CarPlay", "About 30 seconds. The adapter comes back on its Carlinkit firmware.")
        current != null && current.mode.stockKept && !trialSeen ->
            TrialPanel(current, onShown = session::wayBackShown, onContinue = { trialSeen = true }, onGoBack = session::goBackToStock)
        current != null && current.mode == InstallMode.FINAL && !finishSeen -> {
            val acknowledge = {
                FinishNotice.markSeen(context, current.serial)
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

/** The trial: stock CarPlay stays until this drive ends, and the way back (asked once more in a dialog). */
@Composable
private fun TrialPanel(
    install: OneFileInstall,
    onShown: () -> Unit,
    onContinue: () -> Unit,
    onGoBack: () -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(install.mode) { onShown() }
    InstallPanel(
        title = "Welcome to OCBM",
        body = "${installLabel(install)}. Your adapter passed its self-test. Stock CarPlay stays on it until this drive ends.",
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

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
