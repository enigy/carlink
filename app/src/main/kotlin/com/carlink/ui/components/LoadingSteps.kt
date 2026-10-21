package com.carlink.ui.components

import com.carlink.ocbm.Ocbm

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
            "allow usb access",
        )

    // The phone's session has started (the first stream key). Checked before PHONE_WORDS. Only this:
    // "Starting projection…" is the app's SUBSCRIBE to the box, the latch that brings its radios up,
    // and "Re-establishing session…" is the box link recovering, so both are still waking the
    // adapter. Showing "Starting CarPlay" for the first and then stepping back was the launch
    // stutter (truck 2026-10-20).
    private val START_WORDS = listOf("phone connected")

    // The adapter is up and the phone is waited for or in play: BT link up, authenticating,
    // identified, handed off. "Authenticated" also arrives at boot with no phone near (the box replays
    // its last BT phase on every SUBSCRIBE, truck 2026-10-19), but only after the SUBSCRIBE: the
    // adapter is awake then and the app is waiting for the phone, so this step is right for it too.
    private val PHONE_WORDS =
        listOf(
            "waiting for phone",
            "phone found",
            "authenticat",
            "phone identified",
            "identifying",
            "handing off",
            "pairing",
            "phone not",
            "disconnecting phone",
        )

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

    /**
     * The adapter has claimed a projection (CT_PROJ_MODE): WIRELESS_CP right after the phone hands
     * off from Bluetooth to Wi-Fi, WIRED_CP when a phone is plugged in, and the Android Auto modes.
     * That is when CarPlay (or Android Auto) is really starting, ahead of the session's first
     * key ("Phone connected"), which the first video frame follows within half a second.
     */
    fun projectionStarting(mode: Byte): Boolean = mode != Ocbm.PM_NONE

    /** The adapter's projection is Android Auto, so the last step says so. */
    fun isAndroidAuto(mode: Byte): Boolean = mode == Ocbm.PM_WIRED_AA || mode == Ocbm.PM_WIRELESS_AA

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
