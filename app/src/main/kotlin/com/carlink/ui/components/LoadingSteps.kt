package com.carlink.ui.components

/** The connecting screen's three steps (Carlink2 [229]). */
internal enum class LoadingStep { ADAPTER, PHONE, START }

/** What the connecting screen shows: the step, and a plain line when the driver has to act. */
internal data class LoadingStatus(
    val step: LoadingStep,
    val action: String? = null,
)

/**
 * Folds CarlinkManager's status text into three steps. Retries and errors stay in their step; only
 * the messages that ask the driver to do something (or explain a long wait) come through,
 * unchanged, as [LoadingStatus.action].
 *
 * The vocabulary is Carlink3's (OCBM box): "Searching for adapter…", "Phone found — linking…",
 * "Pairing code: 123456", "Phone connected", "Starting projection…". Carlink2's stock-firmware
 * phrases are gone with the firmware.
 */
internal object LoadingSteps {
    // Shown as they are, under the step. Matched by prefix, lowercase.
    private val ACTIONS =
        listOf(
            "pairing code:",
            "adapter not found",
            "adapter not responding",
            "connection unstable",
            "phone not reconnecting",
            "adapter rebooting",
            "usb bridge",
            "usb permission denied",
        )

    // The session itself is starting. Checked before PHONE_WORDS. The Bluetooth handshake texts
    // ("Authenticating…", "Handing off to Wi-Fi…") are NOT here: the box replays its last BT phase on
    // every SUBSCRIBE, so at boot — box health still at rootfs-ok — "Authenticated" arrives before any
    // phone is near, and showing "Starting CarPlay" for it was wrong (truck 2026-10-19).
    private val START_WORDS = listOf("phone connected", "starting projection", "re-establishing")

    // Once the phone is actually in play: BT link up, identified, handed off, or waited for.
    private val PHONE_WORDS =
        listOf("waiting for phone", "phone found", "phone identified", "identifying", "handing off", "pairing", "phone not", "disconnecting phone")

    // "Authenticating…" / "Authenticated" stay on the first step: at boot they are the replayed
    // phase above, and during a real handshake the link-up text has already moved the dots on.

    fun classify(
        statusText: String,
        phoneConnected: Boolean,
    ): LoadingStatus {
        val text = statusText.lowercase()
        val action = statusText.takeIf { ACTIONS.any { text.startsWith(it) } }
        val step =
            when {
                phoneConnected || START_WORDS.any { it in text } -> LoadingStep.START
                PHONE_WORDS.any { it in text } -> LoadingStep.PHONE
                else -> LoadingStep.ADAPTER
            }
        return LoadingStatus(step, action)
    }

    fun label(
        step: LoadingStep,
        androidAuto: Boolean,
    ): String =
        when (step) {
            LoadingStep.ADAPTER -> "Waking the adapter"
            LoadingStep.PHONE -> "Finding your phone"
            LoadingStep.START -> if (androidAuto) "Starting Android Auto" else "Starting CarPlay"
        }
}
