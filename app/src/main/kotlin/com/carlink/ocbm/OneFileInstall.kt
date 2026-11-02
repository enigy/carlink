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

/**
 * What the app shows of the install: its [mode], the installed [version] ("1.0.0") once read, and the adapter's [serial].
 * [selfTest] is the last line of a trial's start-up self-test as the session found it ("PASSED in 41 s"); null when it
 * wasn't read.
 */
data class OneFileInstall(
    val mode: InstallMode,
    val version: String? = null,
    val serial: String = "",
    val selfTest: String? = null,
) {
    companion object {
        /** The adapter's start-up self-test of this start, kept in RAM: its last line is the verdict once it has one. */
        const val SELFTEST_FILE = "/tmp/ocbm_selftest.txt"

        /** `/etc/ocbm/installed_version` ("1.0.0 ocbm 153df7f") to "1.0.0"; null when empty. */
        fun versionOf(installed: String): String? = installed.trim().substringBefore(' ').takeIf { it.isNotEmpty() }

        /** [SELFTEST_FILE]'s last line that isn't blank; null when it has none. */
        fun selfTestOf(file: String): String? = file.lines().lastOrNull { it.isNotBlank() }?.trim()

        /** Whether a self-test line is its verdict, which holds until the adapter restarts: PASSED or FAILED. */
        fun isVerdict(line: String?): Boolean = line != null && (line.startsWith("PASSED") || line.startsWith("FAILED"))
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
 * Whether the app may send MGMT_INSTALL_CONFIRM, the driver having agreed to finish the install: to Remove stock
 * CarPlay (Scott, 2 Nov) or to make room for an update (1 Nov); [InstallSession.confirmFinish] is the only sender.
 * The confirm starts the finish: when the session ends the adapter deletes the Carlinkit stack for good. So only a
 * [mode] of TRIAL, and only from a build allowed to send it ([buildAllows]: every build, Play included, unless
 * built with -Pcarlink.installConfirm=false). Showing the way back confirms nothing.
 */
fun mayConfirmInstall(
    mode: InstallMode?,
    buildAllows: Boolean,
): Boolean = mode == InstallMode.TRIAL && buildAllows
