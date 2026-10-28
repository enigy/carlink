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

// Converting a stock adapter from the app (OCBM one-file install M5 part 3): the plan page's detect,
// confirm, send, installing and restart cards, a download step because the installer comes from a
// GitHub release (decisions of 10-28), and the stick guide where the build's switch is off.

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
        is ConversionPhase.Downloading ->
            if (phase.total > 0) {
                PanelContent("Downloading the installer", megabytes(phase.done, phase.total), progress = fraction(phase.done, phase.total))
            } else {
                PanelContent("Getting the installer", "Checking it before anything is sent.", busy = true)
            }
        is ConversionPhase.Sending ->
            PanelContent(
                title = "Sending the installer",
                body = megabytes(phase.done, phase.total),
                note = "Don't unplug the adapter.",
                progress = fraction(phase.done, phase.total),
            )
        ConversionPhase.Installing ->
            PanelContent(
                title = "The adapter is installing",
                body = "About 90 seconds. The adapter disappears and comes back on its own.",
                note = "Don't turn off the car.",
                busy = true,
            )
        ConversionPhase.Restarting -> PanelContent("Adapter restarting", "Waiting for it to come back as an OCBM adapter.", busy = true)
        is ConversionPhase.Failed -> PanelContent(phase.title, phase.body, actions = listOf(ok))
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
                note = "Keep the car on for about 3 minutes.",
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
                        ?: "It didn't say which model it is, so Carlink won't send it the installer.",
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
            title = "No installer to send",
            body = "This build sends a copy of the installer kept on the head unit. Put $name and $name.sha256 in $folder, then tap Check again.",
            actions = listOf(PanelAction("Check again", onClick = conversion::recheck), notNow),
        )
    } else {
        PanelContent(meta = meta, title = "No installer to send", body = "This build of Carlink has no installer image to send yet.", actions = listOf(notNow))
    }
}

/** "Convert this adapter now?": what is kept, what is not, and what not to do meanwhile. */
@Composable
private fun ConvertDialog(
    onConvert: () -> Unit,
    onDismiss: () -> Unit,
) {
    CarlinkDialog(
        title = "Convert this adapter now?",
        text =
            "The adapter keeps a copy of its own Wi-Fi and Bluetooth settings. There is no full flash backup: that takes " +
                "the USB stick. Don't unplug the adapter or turn off the car.",
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
private const val BYTES_PER_MB = 1_000_000.0
