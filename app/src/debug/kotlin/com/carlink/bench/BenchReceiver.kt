package com.carlink.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.carlink.CarlinkManager
import com.carlink.DisplayOff
import com.carlink.logging.LogRedactor
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ocbm.UpdatePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The Mac's bench hooks (13 Nov), in debug builds only (src/debug: never in a release or on Play), sent from the adb
 * shell. DUMP is the gate: it is signature|privileged|development, so the shell holds it, as can privileged system apps
 * and an app it was granted to with `pm grant`. On Android 14+ a sender that shares its identity is checked too, but
 * `am broadcast` doesn't (its uid reads INVALID_UID). Each logs a "[bench]" line when it acts.
 *
 * The display hooks call the manager's onDisplayOff/onDisplayOn as the display watcher does, but the display stays on:
 * PowerManager.isInteractive() doesn't change, and MainActivity's onStop/onStart (the video pause and resume) don't
 * run. So they test the manager's lifecycle; time to video and the reconnect-while-off rule (scheduleReconnect skips a
 * reconnect while the display is off) need a real display power cycle (the Mac's 14 Nov review, D1).
 *
 *     am broadcast -p com.enigy.carlink3 -a com.enigy.carlink3.BENCH --es do <what> [extras]
 *
 * - `update --es file <path>`: install an OCBM update package through MGMT_UPDATE, each phase logged as "[bench] update
 *   phase: ..." until it ends; refused with no adapter connected. Push it to the app's own folder
 *   (`/sdcard/Android/data/com.enigy.carlink3/files/ocbm/`), which it can read without a permission.
 *   The adapter takes a package of the same VERSION only with its own /tmp/bench_update_any_version.
 * - `display_off`, `display_on`: as the head unit's display going off and on (source "bench").
 * - `grace_ms --ei ms <n>`: the display-off wait for this process; 0 puts back the 30 s.
 * - `flicker --ei off_after_ms <n> --ei on_after_ms <m>`: display on, off n ms into the wake's open, on m ms later (the
 *   stuck-start cases, CL-WAKE-3). Only from dormant: Carlink DISCONNECTED after the display-off wait, else refused.
 * - `order --es macs <a,b>`: the reconnect order, through the Phones page's own path. Each must read AA:BB:CC:DD:EE:FF.
 * - `box_log [--es file box|wl|carplayd_wl] [--ei lines <n>]`: a box log's last n lines (at most [BenchGuards.MAX_LINES], well under
 *   the 4000-line log trail) into this log, pulled as Save Logs does: /tmp/box.log, btd's /tmp/wl.log or carplayd's
 *   /tmp/carplayd_wl.log.
 * - `box_flag --es name <bench_name> [--es value <text>] [--ez on false]`: write the box's /tmp/<bench_name> over CH_FILE
 *   (its content: the value, else "on"; "off" with on false, which the box reads as unset, since a push can't delete).
 *   Only names starting bench_. A flash's reboot clears them: set them again after it.
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
            "box_log" -> boxLog(m, intent.getStringExtra("file") ?: "box", intent.getIntExtra("lines", DEFAULT_LINES))
            "box_flag" -> boxFlag(m, intent.getStringExtra("name").orEmpty(), intent.getStringExtra("value"), intent.getBooleanExtra("on", true))
            else -> logWarn("[bench] unknown: $what", tag = Logger.Tags.USB)
        }
    }

    private suspend fun update(
        m: CarlinkManager,
        path: String,
    ) {
        if (m.state == CarlinkManager.State.DISCONNECTED) {
            logWarn("[bench] update refused: no adapter connected", tag = Logger.Tags.ADAPTR)
            return
        }
        val bytes =
            runCatching { File(path).readBytes() }.getOrElse {
                logWarn("[bench] update: can't read $path (${it.message}); push it to the app's files/ocbm folder", tag = Logger.Tags.ADAPTR)
                return
            }
        logInfo("[bench] update: ${bytes.size} bytes from $path", tag = Logger.Tags.ADAPTR)
        // Refused (not a package, or one already in flight): the phase is the earlier update's, so nothing to follow (CL-1).
        if (!m.adapterUpdate.benchUpdate(bytes)) return
        // Each phase, until it is no longer updating: only the screen collects them otherwise, so a script waiting for an
        // outcome had nothing to wait on (D2). StateFlow conflates, so a quick step can be skipped, never the last.
        withTimeoutOrNull(UPDATE_FOLLOW_MS) {
            m.adapterUpdate.phase.first { p ->
                logInfo("[bench] update phase: $p", tag = Logger.Tags.ADAPTR)
                p !is UpdatePhase.Updating
            }
        } ?: logWarn("[bench] update still running after ${UPDATE_FOLLOW_MS / 1000} s: no longer followed", tag = Logger.Tags.ADAPTR)
    }

    private fun graceMs(ms: Int) {
        // 0 puts back the 30 s; anything else at least BenchGuards.MIN_GRACE_MS, so a typo can't end a session as the display blinks.
        DisplayOff.graceMs = BenchGuards.graceMs(ms)
        logInfo("[bench] display-off wait ${DisplayOff.graceMs} ms", tag = Logger.Tags.USB)
    }

    private suspend fun flicker(
        m: CarlinkManager,
        offAfterMs: Int,
        onAfterMs: Int,
    ) {
        // Only a wake tests the stuck-start cases: from streaming it would only blip the session, and pass falsely.
        if (!BenchGuards.flickerAllowed(m.state, m.dormant)) {
            logWarn("[bench] flicker refused: Carlink is ${m.state}, dormant=${m.dormant}; it needs the display-off wait to have ended the session", tag = Logger.Tags.USB)
            return
        }
        withContext(Dispatchers.Main) { m.onDisplayOn(SOURCE) }
        logInfo("[bench] flicker: display on, state=${m.state}", tag = Logger.Tags.USB)
        delay(offAfterMs.toLong())
        logInfo("[bench] flicker: display off $offAfterMs ms into the wake, state=${m.state}", tag = Logger.Tags.USB)
        withContext(Dispatchers.Main) { m.onDisplayOff(SOURCE) }
        delay(onAfterMs.toLong())
        withContext(Dispatchers.Main) { m.onDisplayOn(SOURCE) }
        logInfo("[bench] flicker: display on again $onAfterMs ms later, state=${m.state}", tag = Logger.Tags.USB)
    }

    private suspend fun order(
        m: CarlinkManager,
        macs: String,
    ) {
        val order =
            BenchGuards.order(macs) ?: run {
                logWarn("[bench] order refused: $macs is not a list of Bluetooth addresses, each once", tag = Logger.Tags.ADAPTR)
                return
            }
        withContext(Dispatchers.Main) { m.setPhoneOrder(order) }
    }

    private fun boxLog(
        m: CarlinkManager,
        file: String,
        lines: Int,
    ) {
        val path =
            BenchGuards.logPath(file) ?: run {
                logWarn("[bench] box_log: no log named $file (${BenchGuards.logNames.joinToString()})", tag = Logger.Tags.ADAPTR)
                return
            }
        m
            .boxFileTail(path, lines.coerceIn(1, BenchGuards.MAX_LINES))
            .onSuccess { tail -> tail.forEach { logInfo("[bench] $file: ${LogRedactor.line(it)}", tag = Logger.Tags.ADAPTR) } }
            .onFailure { logWarn("[bench] box_log $path: ${it.message}", tag = Logger.Tags.ADAPTR) }
    }

    private fun boxFlag(
        m: CarlinkManager,
        name: String,
        value: String?,
        on: Boolean,
    ) {
        if (!BenchGuards.isFlag(name)) {
            logWarn("[bench] box_flag refused: $name is not a bench_ name", tag = Logger.Tags.ADAPTR)
            return
        }
        val content = if (on) value ?: "on" else "off"
        m
            .pushToBox("/tmp/$name", "$content\n".toByteArray(Charsets.UTF_8))
            .onSuccess { logInfo("[bench] box_flag /tmp/$name = $content", tag = Logger.Tags.ADAPTR) }
            .onFailure { logWarn("[bench] box_flag /tmp/$name not written: ${it.message}", tag = Logger.Tags.ADAPTR) }
    }

    /** DUMP is the gate; on Android 14+ a sender that shares its identity must also be the shell (2000) or root. */
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
        const val UPDATE_FOLLOW_MS = 600_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
