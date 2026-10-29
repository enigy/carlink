package com.carlink.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.carlink.CarlinkManager
import com.carlink.convert.ConversionPhase
import com.carlink.convert.InstallerImage
import com.carlink.convert.Offer
import com.carlink.convert.REQUIRED_FIRMWARE
import com.carlink.convert.StockConversion
import java.util.Locale

// Converting a stock adapter from the app (OCBM one-file install M5 part 3): the plan page's detect and
// confirm cards, a download step because the installer comes from a GitHub release (decisions of 10-28),
// the stick it goes to the adapter on (29 Oct), and the stick guide where the build's switch is off.

/**
 * The connecting screen's panel for a stock adapter, or [otherwise] when there is none. Converting is
 * asked once more in a dialog, so a stray tap cannot start it.
 */
@Composable
internal fun ConversionPanelOr(
    carlinkManager: CarlinkManager,
    otherwise: @Composable () -> Unit,
) {
    val conversion = carlinkManager.stockConversion
    val phase by conversion.phase.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    val panel = conversionPanel(phase, conversion) { confirming = true }
    if (panel == null) otherwise() else OverlayPanel(panel)
    if (confirming && (phase as? ConversionPhase.Offered)?.offer == Offer.CONVERT) {
        ConvertDialog(
            onConvert = {
                confirming = false
                conversion.convert()
            },
            onDismiss = { confirming = false },
        )
    }
}

/** The panel for [phase]; null when there is nothing to say. */
private fun conversionPanel(
    phase: ConversionPhase,
    conversion: StockConversion,
    onConvert: () -> Unit,
): PanelContent? {
    val ok = PanelAction("OK", onClick = conversion::dismiss)
    return when (phase) {
        ConversionPhase.Idle, ConversionPhase.Declined -> null
        is ConversionPhase.StickOnly ->
            PanelContent(
                meta = "${phase.usbId} · Carlinkit firmware",
                title = STOCK_TITLE,
                body =
                    "Carlink works with adapters converted to OCBM. This version of Carlink doesn't convert them; the OCBM " +
                        "installer on a USB stick does." + InstallerImage.STICK_GUIDE?.let { " The guide: $it" }.orEmpty(),
                actions = listOf(ok),
            )
        is ConversionPhase.Reading ->
            PanelContent(title = "Carlinkit adapter found", body = "Asking it for its model and firmware version.", meta = phase.usbId, busy = true)
        is ConversionPhase.Offered -> offerPanel(phase, conversion, onConvert)
        is ConversionPhase.Failed -> PanelContent(phase.title, phase.body, actions = listOf(ok))
        is ConversionPhase.StickInFirst ->
            PanelContent(
                meta = "${phase.usbId} · a USB stick",
                title = "Is there a USB stick in your adapter?",
                body = "If this is your adapter with a USB stick in it, take the stick out. Carlink checks the adapter first and asks for the stick when it's time.",
                actions = listOf(ok),
            )
        is ConversionPhase.Converting -> stickPanel(phase, conversion)
    }
}

/** From "Convert" on: getting the installer, the stick it goes on, and the adapter's restart with it. */
private fun stickPanel(
    phase: ConversionPhase.Converting,
    conversion: StockConversion,
): PanelContent {
    val cancel = PanelAction("Cancel", PanelActionLook.QUIET, conversion::dismiss)
    return when (phase) {
        is ConversionPhase.Downloading ->
            if (phase.total > 0) {
                PanelContent("Downloading the installer", megabytes(phase.done, phase.total), progress = fraction(phase.done, phase.total))
            } else {
                PanelContent("Getting the installer", "Checking it before anything is written.", busy = true)
            }
        ConversionPhase.WaitingForStick ->
            PanelContent(
                title = "Put a USB stick into the adapter",
                body = "A FAT32 stick of 16 GB or less, in the adapter's own USB port. Leave the adapter plugged in.",
                note = KEEP_STICK,
                busy = true,
                actions = listOf(cancel),
            )
        ConversionPhase.ReadingStick ->
            PanelContent(
                title = "Reading the stick",
                body = "Checking it has room for the installer and the backup. If Android asks, allow the Carlink USB bridge to use it.",
                busy = true,
            )
        is ConversionPhase.StickReady -> stickReadyPanel(phase, conversion, cancel)
        is ConversionPhase.StickProblem ->
            PanelContent(phase.title, phase.body, note = "Carlink reads the stick again when it goes back into the adapter.", busy = true, actions = listOf(cancel))
        is ConversionPhase.WritingStick ->
            PanelContent(
                title = "Writing the installer to the stick",
                body = megabytes(phase.done, phase.total),
                note = "Don't unplug the adapter or take the stick out.",
                progress = fraction(phase.done, phase.total),
            )
        ConversionPhase.CheckingStick -> PanelContent("Checking the installer on the stick", "Reading it back and comparing its SHA-256.", busy = true)
        ConversionPhase.ReplugAdapter ->
            PanelContent(
                title = "Unplug the adapter and plug it back in",
                body = "Unplug the adapter and plug it back in, with the stick still in it. It installs OCBM from the stick as it starts.",
                note = KEEP_STICK,
                actions = listOf(PanelAction("Close", PanelActionLook.QUIET, conversion::dismiss)),
            )
        is ConversionPhase.InstallingFromStick ->
            PanelContent(
                title = "The adapter is installing OCBM",
                body = phase.hint ?: "From the stick. It comes back as an OCBM adapter when it's done, within a few minutes.",
                note = "Don't unplug the adapter, take the stick out or turn off the car.",
                busy = true,
            )
    }
}

/** What is on the stick before writing (the installer's last report, an image already there), or the adapter back with it. */
private fun stickReadyPanel(
    ready: ConversionPhase.StickReady,
    conversion: StockConversion,
    cancel: PanelAction,
): PanelContent {
    val report = ready.report
    val said = report?.let { r -> "Its last report: ${r.headline}${r.installer?.let { " ($it)" }.orEmpty()}. ${r.paragraphs.joinToString(" ")}" }
    val write = PanelAction(if (ready.retry) "Try again" else "Write the installer", onClick = conversion::writeStick)
    val note = if (ready.retry) RETRY_NOTE else null
    return when {
        ready.returned && ready.retry -> PanelContent("The adapter didn't convert", said.orEmpty(), note = note, actions = listOf(write, cancel))
        ready.returned ->
            PanelContent(
                title = "The adapter didn't convert",
                body = said ?: "It came back sharing the stick, and there is no OCBM_RESULT.txt on it.",
                actions = listOf(PanelAction("OK", onClick = conversion::dismiss)),
            )
        else -> {
            val replaces = if (ready.hasImage) "It already holds an ${InstallerImage.FILE_NAME}: writing replaces it." else ""
            PanelContent(
                title = if (report != null) "This stick was used for OCBM before" else "Replace the installer on the stick?",
                body = listOfNotNull(said, replaces).joinToString(" ").trim(),
                note = note,
                actions = listOf(write, cancel),
            )
        }
    }
}

/** What the adapter said it is, and what it is offered: the detect cards. */
private fun offerPanel(
    offered: ConversionPhase.Offered,
    conversion: StockConversion,
    onConvert: () -> Unit,
): PanelContent {
    val info = offered.info
    val meta = listOfNotNull(offered.usbId, info.firmware, info.product).joinToString(" · ")
    val ok = PanelAction("OK", onClick = conversion::dismiss)
    val notNow = PanelAction("Not now", PanelActionLook.QUIET, conversion::dismiss)
    return when (offered.offer) {
        Offer.CONVERT ->
            PanelContent(
                meta = meta,
                title = STOCK_TITLE,
                body = "Convert it to OCBM for current CarPlay. Stock CarPlay stays on the adapter until your first drive with Carlink ends.",
                note = "It takes a FAT32 USB stick, 16 GB or less, in the adapter.",
                actions = listOf(PanelAction("Convert to OCBM", onClick = onConvert), notNow),
            )
        Offer.NO_IMAGE -> noImagePanel(meta, conversion, notNow)
        Offer.UPDATE_FIRST ->
            PanelContent(
                meta = meta,
                title = "Update the Carlinkit firmware first",
                body =
                    "Conversion needs firmware $REQUIRED_FIRMWARE${info.firmware?.let { "; this adapter has $it" }.orEmpty()}. " +
                        "Install Carlinkit's own update, then plug the adapter in again.",
                actions = listOf(ok),
            )
        Offer.UNSUPPORTED ->
            PanelContent(
                meta = meta,
                title = "Carlink can't convert this adapter",
                body =
                    info.product?.let { "The installer is for the Carlinkit A15W (CPC200-CCPA). This adapter says it is a $it." }
                        ?: "It didn't say which model it is, so Carlink won't convert it.",
                actions = listOf(ok),
            )
    }
}

/** Convertible, but there is no installer: a debug build says where its bench copy goes. */
private fun noImagePanel(
    meta: String,
    conversion: StockConversion,
    notNow: PanelAction,
): PanelContent {
    val folder = conversion.benchFolder
    val name = InstallerImage.FILE_NAME
    return if (folder != null) {
        PanelContent(
            meta = meta,
            title = "No installer",
            body = "This build writes a copy of the installer kept on the head unit. Put $name and $name.sha256 in $folder, then tap Check again.",
            actions = listOf(PanelAction("Check again", onClick = conversion::recheck), notNow),
        )
    } else {
        PanelContent(meta = meta, title = "No installer", body = "This build of Carlink has no installer image yet.", actions = listOf(notNow))
    }
}

/** "Convert this adapter now?": the stick it takes, the backup that goes on it, and what not to do meanwhile. */
@Composable
private fun ConvertDialog(
    onConvert: () -> Unit,
    onDismiss: () -> Unit,
) {
    CarlinkDialog(
        title = "Convert this adapter now?",
        text =
            "Carlink writes the installer to a FAT32 USB stick (16 GB or less) that you put into the adapter. As it installs, the " +
                "adapter saves a full backup of its flash to the stick. $KEEP_STICK Don't turn off the car while it installs.",
        confirmLabel = "Convert",
        onConfirm = onConvert,
        onDismiss = onDismiss,
        icon = Icons.Default.SystemUpdate,
        weight = DialogWeight.CAUTION,
    )
}

/** "1.4 of 2.2 MB". */
private fun megabytes(
    done: Long,
    total: Long,
): String = "${String.format(Locale.US, "%.1f", done / BYTES_PER_MB)} of ${String.format(Locale.US, "%.1f", total / BYTES_PER_MB)} MB"

private fun fraction(
    done: Long,
    total: Long,
): Float = if (total > 0) done.toFloat() / total else 0f

private const val STOCK_TITLE = "This adapter runs Carlinkit firmware"
private const val KEEP_STICK = "Keep the stick afterwards, and keep it private: the backup holds the adapter's Wi-Fi password and Bluetooth keys."
private const val RETRY_NOTE = "Try again writes the installer with an empty OCBM_RETRY file beside it, as the report asks."
private const val BYTES_PER_MB = 1_000_000.0
