package com.carlink.ocbm

/**
 * The OCBM one-file install as the adapter reports it (OCBM one-file install handoff, 10-28; enigy/ocbm
 * 153df7f): MGMT_INFO's `"install"` on an adapter with CAP_INSTALL. A stick or the app installs OCBM
 * next to stock CarPlay ([TRIAL]); the app confirms the trial while it shows the way back
 * ([CONFIRMED]); when that session ends the adapter removes the Carlinkit stack ([FINAL]). Until then
 * stock is kept, and MGMT_GO_STOCK takes the adapter back to it.
 */
enum class InstallMode(
    val wire: String,
) {
    TRIAL("trial"),
    CONFIRMED("confirmed"),
    FINAL("final"),
    ;

    /** Stock CarPlay is still on the adapter, so the way back is open. */
    val stockKept: Boolean get() = this != FINAL

    companion object {
        /** The mode for MGMT_INFO's `"install"`; null for `"none"` (not a one-file install) or anything else. */
        fun of(wire: String?): InstallMode? = entries.firstOrNull { it.wire == wire }
    }
}

/** What the app shows of the install: its [mode], the installed [version] ("1.0.0") once read, and the adapter's [serial]. */
data class OneFileInstall(
    val mode: InstallMode,
    val version: String? = null,
    val serial: String = "",
) {
    companion object {
        /** `/etc/ocbm/installed_version` ("1.0.0 ocbm 153df7f") to "1.0.0"; null when empty. */
        fun versionOf(installed: String): String? = installed.trim().substringBefore(' ').takeIf { it.isNotEmpty() }
    }
}

/** What became of a request to go back to stock CarPlay (MGMT_GO_STOCK). */
enum class GoBackResult {
    /** ACK status 0: the adapter is restarting on stock CarPlay (about 30 s). */
    GOING,

    /** ACK status 1: nothing to go back to (already final, or not a one-file install). */
    REFUSED,

    /** No ACK: the adapter did not answer. */
    NO_ANSWER,
}

/**
 * Whether the app may send MGMT_INSTALL_CONFIRM now. The confirm starts the finish: when the session
 * ends the adapter deletes the Carlinkit stack for good. So only a [mode] of TRIAL, only while the
 * app shows the way back ([wayBackShown]), once a session ([alreadyTried]), and only from a build
 * allowed to send it ([buildAllows]; off until a way back after the finish is built and proven and
 * the owner agrees).
 */
fun mayConfirmInstall(
    mode: InstallMode?,
    wayBackShown: Boolean,
    buildAllows: Boolean,
    alreadyTried: Boolean,
): Boolean = mode == InstallMode.TRIAL && wayBackShown && buildAllows && !alreadyTried
