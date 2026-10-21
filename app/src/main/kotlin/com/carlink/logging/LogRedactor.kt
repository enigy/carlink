package com.carlink.logging

import com.carlink.BuildConfig

/**
 * Takes personal data out of log text before it is written to shared storage (security review M4).
 *
 * Drive logs and Save logs land in `Downloads/Carlink3`, which outlives the app and is readable by
 * any app with all-files access, so every persisted line goes through [line]: app lines, the box's
 * CH_LOG stream and the pulled box files alike. Logcat is left alone. What goes:
 * - Bluetooth/Wi-Fi MACs keep their last two octets (`..25:10`), enough to tell phones apart.
 * - The phone's name in the box's identity JSON (`"name":"…"` next to `deviceID`).
 *
 * Callers, the phone name in app lines and road names are handled where they are logged
 * ([com.carlink.callui.CallState.logLabel], [place]).
 */
object LogRedactor {
    // Six octets, not part of a longer colon-hex run (a certificate fingerprint stays whole).
    private val MAC = Regex("(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{2}[:-]){4}([0-9A-Fa-f]{2})[:-]([0-9A-Fa-f]{2})(?![0-9A-Fa-f:])")
    private val IDENTITY_NAME = Regex("\"(name|deviceName)\":\"[^\"]*\"")

    fun line(text: String): String {
        var out = MAC.replace(text) { "..${it.groupValues[1]}:${it.groupValues[2]}" }
        if ("deviceID" in out) out = IDENTITY_NAME.replace(out) { "\"${it.groupValues[1]}\":\"<phone>\"" }
        return out
    }

    /**
     * A road or instruction for a log line: itself with Detailed logging on (or in a debug build),
     * else `<road>`. INFO lines reach the trail and drive log whatever the logcat filter, and a
     * list of roads is a route history.
     */
    fun place(name: String?): String =
        when {
            name.isNullOrEmpty() -> name.orEmpty()
            BuildConfig.DEBUG || Logger.isReleaseVerbose() -> name
            else -> "<road>"
        }

    /** [line] over every line of a UTF-8 log file. */
    fun text(bytes: ByteArray): ByteArray =
        bytes
            .toString(Charsets.UTF_8)
            .split('\n')
            .joinToString("\n") { line(it) }
            .toByteArray(Charsets.UTF_8)
}
