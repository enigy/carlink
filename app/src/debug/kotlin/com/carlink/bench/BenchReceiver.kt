package com.carlink.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.carlink.CarlinkManager
import com.carlink.DisplayOff
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Mac's bench hooks (13 Nov), in debug builds only (src/debug: never in a release or on Play), sent from the adb
 * shell. The receiver needs DUMP, which only the shell and root hold, and on Android 14+ a sender that shares its
 * identity must be one of them. Each logs a "[bench]" line when it acts.
 *
 *     am broadcast -p com.enigy.carlink3 -a com.enigy.carlink3.BENCH --es do <what> [extras]
 *
 * - `update --es file <path>`: install an OCBM update package through MGMT_UPDATE, reported as any update is. Push it to
 *   the app's own folder (`/sdcard/Android/data/com.enigy.carlink3/files/ocbm/`), which it can read without a permission.
 *   The adapter takes a package of the same VERSION only with its own /tmp/bench_update_any_version.
 * - `display_off`, `display_on`: as the head unit's display going off and on (source "bench").
 * - `grace_ms --ei ms <n>`: the display-off wait for this process; 0 puts back the 30 s.
 * - `flicker --ei off_after_ms <n> --ei on_after_ms <m>`: from dormant, display on, off n ms into the wake's open, on m ms
 *   later (the stuck-start cases, CL-WAKE-3).
 * - `order --es macs <a,b>`: the reconnect order, through the Phones page's own path.
 * - `box_log --ei lines <n>`: the box log's last n lines into this log, as Save Logs pulls it.
 */
class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!fromShell()) {
            logWarn("[bench] refused: not from the adb shell", tag = Logger.Tags.USB)
            return
        }
        val m = CarlinkManager.live
        val what = intent.getStringExtra("do").orEmpty()
        if (m == null) {
            logWarn("[bench] $what: no Carlink session manager running", tag = Logger.Tags.USB)
            return
        }
        logInfo("[bench] $what", tag = Logger.Tags.USB)
        scope.launch { act(m, what, intent) }
    }

    private suspend fun act(
        m: CarlinkManager,
        what: String,
        intent: Intent,
    ) {
        when (what) {
            "update" -> update(m, intent.getStringExtra("file").orEmpty())
            "display_off" -> withContext(Dispatchers.Main) { m.onDisplayOff(SOURCE) }
            "display_on" -> withContext(Dispatchers.Main) { m.onDisplayOn(SOURCE) }
            "grace_ms" -> graceMs(intent.getIntExtra("ms", 0))
            "flicker" -> flicker(m, intent.getIntExtra("off_after_ms", DEFAULT_OFF_AFTER_MS), intent.getIntExtra("on_after_ms", DEFAULT_ON_AFTER_MS))
            "order" -> order(m, intent.getStringExtra("macs").orEmpty())
            "box_log" -> boxLog(m, intent.getIntExtra("lines", DEFAULT_LINES))
            else -> logWarn("[bench] unknown: $what", tag = Logger.Tags.USB)
        }
    }

    private fun update(
        m: CarlinkManager,
        path: String,
    ) {
        val bytes = runCatching { File(path).readBytes() }.getOrElse {
            logWarn("[bench] update: can't read $path (${it.message}); push it to the app's files/ocbm folder", tag = Logger.Tags.ADAPTR)
            return
        }
        logInfo("[bench] update: ${bytes.size} bytes from $path", tag = Logger.Tags.ADAPTR)
        m.adapterUpdate.benchUpdate(bytes)
    }

    private fun graceMs(ms: Int) {
        DisplayOff.graceMs = if (ms > 0) ms.toLong() else DisplayOff.GRACE_MS
        logInfo("[bench] display-off wait ${DisplayOff.graceMs} ms", tag = Logger.Tags.USB)
    }

    private suspend fun flicker(
        m: CarlinkManager,
        offAfterMs: Int,
        onAfterMs: Int,
    ) {
        withContext(Dispatchers.Main) { m.onDisplayOn(SOURCE) }
        delay(offAfterMs.toLong())
        withContext(Dispatchers.Main) { m.onDisplayOff(SOURCE) }
        delay(onAfterMs.toLong())
        withContext(Dispatchers.Main) { m.onDisplayOn(SOURCE) }
        logInfo("[bench] flicker done: off $offAfterMs ms into the wake, on $onAfterMs ms later", tag = Logger.Tags.USB)
    }

    private suspend fun order(
        m: CarlinkManager,
        macs: String,
    ) {
        val order = macs.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        withContext(Dispatchers.Main) { m.setPhoneOrder(order) }
    }

    private fun boxLog(
        m: CarlinkManager,
        lines: Int,
    ) {
        val tail = m.boxLogTail(lines.coerceIn(1, MAX_LINES))
        if (tail == null) {
            logWarn("[bench] box_log: no adapter connected", tag = Logger.Tags.ADAPTR)
            return
        }
        tail.forEach { logInfo("[bench] box.log: $it", tag = Logger.Tags.ADAPTR) }
    }

    /** The adb shell (2000) or root: DUMP already says so; on Android 14+ a sender that shares its identity is checked too. */
    private fun fromShell(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val uid = sentFromUid
        return uid == Process.INVALID_UID || uid == SHELL_UID || uid == Process.ROOT_UID
    }

    private companion object {
        const val SOURCE = "bench"
        const val SHELL_UID = 2000
        const val DEFAULT_OFF_AFTER_MS = 150
        const val DEFAULT_ON_AFTER_MS = 100
        const val DEFAULT_LINES = 400
        const val MAX_LINES = 5000
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
