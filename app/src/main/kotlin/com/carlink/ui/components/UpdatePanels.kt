package com.carlink.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.carlink.CarlinkManager
import com.carlink.ocbm.UpdateOffer
import com.carlink.ocbm.UpdatePhase

// Updating an adapter already on OCBM (remote updates plan, U2): the conversion's panels again (downloading,
// sending, installing, restarting), then the outcome, which stays until OK.

/** The connecting screen's panel for an adapter update in flight or just ended, or [otherwise] when there is none. */
@Composable
internal fun UpdatePanelOr(
    carlinkManager: CarlinkManager,
    otherwise: @Composable () -> Unit,
) {
    val update = carlinkManager.adapterUpdate
    val phase by update.phase.collectAsState()
    val panel = updatePanel(phase, update::dismiss)
    if (panel == null) otherwise() else OverlayPanel(panel)
}

/** The panel for [phase]; null when there is nothing to say. */
internal fun updatePanel(
    phase: UpdatePhase,
    onOk: () -> Unit,
): PanelContent? {
    val ok = listOf(PanelAction("OK", onClick = onOk))
    return when (phase) {
        UpdatePhase.Idle -> null
        is UpdatePhase.Downloading ->
            if (phase.total > 0) {
                PanelContent("Downloading OCBM ${phase.to}", megabytes(phase.done, phase.total), progress = fraction(phase.done, phase.total))
            } else {
                PanelContent("Getting OCBM ${phase.to}", "Checking it before anything is sent.", busy = true)
            }
        is UpdatePhase.Sending ->
            PanelContent("Sending the update to the adapter", megabytes(phase.done, phase.total), note = KEEP_ON, progress = fraction(phase.done, phase.total))
        is UpdatePhase.Installing ->
            PanelContent("The adapter is installing OCBM ${phase.to}", "It checks the update, then restarts. CarPlay is back in a few minutes.", note = KEEP_ON, busy = true)
        is UpdatePhase.Restarting ->
            PanelContent("The adapter is restarting", "On OCBM ${phase.to}. It checks itself as it starts.", note = KEEP_ON, busy = true)
        is UpdatePhase.Checking ->
            PanelContent(
                "Checking OCBM ${phase.to}",
                "The adapter runs its start-up self-test and reconnects to Carlink. If either fails, it puts back the version it had.",
                busy = true,
            )
        is UpdatePhase.Updated -> PanelContent("Adapter updated to ${phase.version}", "It passed its start-up self-test and reconnected to Carlink.", actions = ok)
        is UpdatePhase.Kept -> PanelContent(keptTitle(phase), keptText(phase), actions = ok)
        is UpdatePhase.Refused -> PanelContent(REFUSED_TITLE, phase.reason, note = "The adapter keeps the version it has.", actions = ok)
        is UpdatePhase.Failed -> PanelContent(phase.title, phase.body, actions = ok)
    }
}

/** Settings' title for the update row: the offer, or the update in flight or its outcome. */
internal fun updateTitle(
    offer: UpdateOffer,
    phase: UpdatePhase,
): String =
    when (phase) {
        is UpdatePhase.Updating -> "Updating to OCBM ${phase.to}"
        is UpdatePhase.Updated -> "Adapter updated to ${phase.version}"
        is UpdatePhase.Kept -> keptTitle(phase)
        is UpdatePhase.Refused -> REFUSED_TITLE
        is UpdatePhase.Failed -> phase.title
        UpdatePhase.Idle -> offer.pkg?.let { "OCBM ${it.version} available" }.orEmpty()
    }

/** Settings' line under the update row: progress, the outcome's reason, the notes, or why it can't go in place. */
internal fun updateLine(
    offer: UpdateOffer,
    phase: UpdatePhase,
): String? =
    when (phase) {
        is UpdatePhase.Downloading -> if (phase.total > 0) "Downloading: ${megabytes(phase.done, phase.total)}" else "Getting the package"
        is UpdatePhase.Sending -> "Sending to the adapter: ${megabytes(phase.done, phase.total)}"
        is UpdatePhase.Installing -> "The adapter is installing it, then restarts"
        is UpdatePhase.Restarting -> "The adapter is restarting"
        is UpdatePhase.Checking -> "The adapter is running its start-up self-test"
        is UpdatePhase.Updated -> null
        is UpdatePhase.Kept -> sentence(phase.reason)
        is UpdatePhase.Refused -> phase.reason
        is UpdatePhase.Failed -> phase.body
        UpdatePhase.Idle -> offerLine(offer)
    }

/** The offer's line: its notes, or why it can't go in place. */
private fun offerLine(offer: UpdateOffer): String? =
    when (offer) {
        is UpdateOffer.InPlace -> offer.pkg.notes.ifEmpty { null }
        is UpdateOffer.FinishFirst -> "${offer.why} Finishing the install makes the room."
        is UpdateOffer.AfterThisDrive -> "Update it after this drive: the adapter finishes its install when the drive ends."
        is UpdateOffer.CannotTake -> offer.why
        UpdateOffer.None -> null
    }

/** "Update the adapter to OCBM 1.1.0?": what the driver loses meanwhile, and what happens if it fails. */
@Composable
internal fun UpdateDialog(
    offer: UpdateOffer.InPlace,
    have: String?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val notes = if (offer.pkg.notes.isEmpty()) "" else "${offer.pkg.notes}\n\n"
    CarlinkDialog(
        title = "Update the adapter to OCBM ${offer.pkg.version}?",
        text =
            notes + "CarPlay goes away for a few minutes while the adapter installs it and restarts. Keep the car on. If the update " +
                "doesn't pass the adapter's self-test or doesn't connect to Carlink, the adapter puts back " +
                "${have?.let { "OCBM $it" } ?: "the version it has"}.",
        confirmLabel = "Update",
        onConfirm = onUpdate,
        onDismiss = onDismiss,
        icon = Icons.Default.SystemUpdate,
        weight = DialogWeight.CAUTION,
    )
}

/**
 * A trial adapter without the room for the update (Scott, 1 Nov): finish the install, which deletes the copy of stock
 * CarPlay firmware the adapter keeps, so the update can go in. Declining leaves the update uninstalled.
 */
@Composable
internal fun FinishFirstDialog(
    offer: UpdateOffer.FinishFirst,
    onFinish: () -> Unit,
    onDecline: () -> Unit,
) {
    CarlinkDialog(
        title = "Finish the install to make room?",
        text =
            "${offer.why} Finishing the install makes the room: it deletes the copy of stock CarPlay firmware on the adapter, " +
                "and after that the backup on your USB stick is the way back to stock. The install finishes when this drive " +
                "ends, and OCBM ${offer.pkg.version} can go in on the next one.",
        confirmLabel = "Finish the install",
        onConfirm = onFinish,
        onDismiss = onDecline,
        icon = Icons.Default.SystemUpdate,
        weight = DialogWeight.DANGER,
    )
}

/** "Kept 1.0.0: the update did not pass its self-test", as the plan has it, or that it was undone for another reason. */
private fun keptTitle(kept: UpdatePhase.Kept): String =
    if ("self-test" in kept.reason) "Kept ${kept.version}: the update did not pass its self-test" else "Kept ${kept.version}: the update was undone"

/**
 * The adapter's reason in plain words (Scott, 5 Nov): it said "ocbmd heard the host for 121 s and never answered it", and the
 * panel showed that as it came. Known reasons get a sentence naming both versions; any other is shown as it came.
 */
internal fun keptText(kept: UpdatePhase.Kept): String {
    val update = kept.to?.let { "OCBM $it" }
    val back = if (kept.version.firstOrNull()?.isDigit() == true) "OCBM ${kept.version}" else kept.version
    val r = kept.reason
    return when {
        "never answered" in r -> "The adapter on ${update ?: "the update"} didn't answer Carlink, so it put back $back."
        "self-test failed" in r -> "${update ?: "The update"} failed the adapter's start-up self-test, so it put back $back."
        "times in a row" in r -> "${update ?: "The update"} didn't start properly twice in a row, so the adapter put back $back."
        "power was lost" in r -> "Power was lost during the update, so the adapter put back $back."
        else -> sentence(r)
    }
}

/** The adapter's reason as a sentence: "its start-up self-test failed" to "Its start-up self-test failed." */
private fun sentence(reason: String): String = reason.replaceFirstChar { it.uppercaseChar() }.let { if (it.isEmpty() || it.endsWith('.')) it else "$it." }

private const val KEEP_ON = "Don't unplug the adapter or turn off the car."
private const val REFUSED_TITLE = "The adapter didn't take the update"
