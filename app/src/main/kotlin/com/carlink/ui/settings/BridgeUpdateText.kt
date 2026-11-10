package com.carlink.ui.settings

import com.carlink.usb.BridgeUpdateStep
import com.carlink.usb.PinnedBridge

/** The words of the bridge's update in Settings, apart from the composables so they are testable. */
internal object BridgeUpdateText {
    fun available(pin: PinnedBridge): String = "Bridge ${pin.versionName} available"

    const val CONFIRM_TITLE = "Update the USB bridge?"

    fun confirmText(pin: PinnedBridge): String =
        "Carlink asks the USB bridge to download bridge ${pin.versionName} from Carlink's GitHub release, check it against " +
            "the SHA-256 this Carlink pins, and install it over itself.\n\n" +
            "CarPlay stops for a moment while the bridge restarts. The head unit may ask you to let Carlink USB Bridge install " +
            "apps, and then to confirm the update. Nothing else is installed."

    const val MANUAL_TITLE = "Update the USB bridge by hand"

    /** The setup panel's USB drive and Files steps, worded for an update: a bridge before 1.0.9 can't update itself. */
    fun manualText(pin: PinnedBridge): String =
        "This bridge can't update itself yet, so bridge ${pin.versionName} goes on once by hand, as it was installed. While parked:\n" +
            "1. Copy ${pin.fileName} from ${pin.releasePage} to a USB drive. Its SHA-256 is ${pin.sha256}.\n" +
            "2. Plug the drive into a free USB port.\n" +
            "3. Tap Open Files, open the drive, and tap the APK. If asked, allow Files to install apps, then choose Update.\n" +
            "4. Come back to Carlink. From ${pin.versionName} on, the bridge updates itself from here."

    const val NOT_ALLOWED = "The head unit doesn't let Carlink USB Bridge install apps yet. Allow it, then tap Update again."
    const val NOT_ALLOWED_NO_PAGE =
        "The head unit doesn't let Carlink USB Bridge install apps yet. Allow it in the head unit's " +
            "Settings › Apps › Carlink USB Bridge › Install unknown apps, then tap Update again."

    /** The row's line for [step]; null when there is nothing to say. */
    fun stepLine(step: BridgeUpdateStep): String? =
        when (step) {
            BridgeUpdateStep.Idle, BridgeUpdateStep.NotAllowed -> null
            BridgeUpdateStep.Asking -> "Asking the USB bridge…"
            BridgeUpdateStep.Downloading -> "Downloading…"
            BridgeUpdateStep.Checking -> "Checking the download…"
            BridgeUpdateStep.Installing -> "Installing…"
            BridgeUpdateStep.Confirming -> "Confirm the update on the screen the head unit shows."
            BridgeUpdateStep.Restarting -> "The bridge is restarting…"
            is BridgeUpdateStep.Updated -> "Updated to bridge ${step.version}."
            is BridgeUpdateStep.Failed -> "The update didn't happen: ${step.message}"
        }

    /** Update is for parked, and once at a time. */
    fun updateEnabled(
        driving: Boolean,
        step: BridgeUpdateStep,
    ): Boolean = !driving && !step.running
}
