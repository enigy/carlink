package com.carlink.logging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own account of the session: a bounded ring of the last [CAPACITY] formatted INFO-and-above
 * lines, stamped here. Independent of logcat and of whatever filters it (the release build's
 * WARN-only policy, the platform), so Settings → Adapter → Save Logs can always write the
 * [CLUSTER]/[NAVI]/[BRIDGE] story. ~4000 lines is a few hundred KB at most. Fed by [Logger.log].
 */
object LogTrail {
    private const val CAPACITY = 4000
    private val lines = ArrayDeque<String>(CAPACITY)
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun record(
        level: Logger.Level,
        message: String,
        throwable: Throwable?,
    ) {
        val stamp = synchronized(time) { time.format(Date()) }
        val line = "$stamp ${level.name.first()} $message" + (throwable?.let { " :: $it" } ?: "")
        synchronized(lines) {
            if (lines.size >= CAPACITY) lines.removeFirst()
            lines.addLast(line)
        }
        DriveLog.appLine(line)
    }

    /** The trail so far, oldest first, one line each. */
    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }
}
