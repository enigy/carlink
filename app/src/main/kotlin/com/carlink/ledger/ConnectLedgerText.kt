package com.carlink.ledger

import com.carlink.ledger.ConnectRecord.Origin
import com.carlink.ledger.ConnectRecord.Outcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The ledger as readable text, for Save Logs and the log line each connect gets: a two-line header, then one line per
 * connect, newest first. A line, for a wake that showed a picture:
 *
 *     2026-11-16 07:42:13 wake, adapter listed: picture +3412 ms | SUBSCRIBE +612 ms, video in +3190 ms, decoder ready
 *     +905 ms, picture +3412 ms, STREAMING +4105 ms | 1 start, screen in front +214 ms, not suspended | adapter up
 *     1802.4 s, preload done, absent pages 0, resets 0, nudges 0+0, PSK mismatches 0 | [timeline] v1 a=1 b=2
 *
 * (one line in the file). Times are from the connect's origin; "-" is a step that never came. "video in" (the first
 * video from the adapter reaching the decoder) and "decoder ready" are there only when the record has them: a connect
 * with no picture, or one kept before they were measured, reads as it always did.
 */
object ConnectLedgerText {
    private const val MS_PER_S = 1000
    private const val MS_PER_TENTH = 100

    /** The whole ledger: header, then newest first. [zone] is the head unit's own, as the other saved logs use. */
    fun text(
        ledger: ConnectLedger,
        zone: TimeZone = TimeZone.getDefault(),
    ): String =
        buildString {
            append("Carlink connect ledger: ${ledger.records.size} connects (the last ${ConnectLedger.CAP} are kept), newest first. ")
            append("Times are from each one's origin: the display on for a wake, the process start for an app start, ")
            append("the start itself for a reconnect.\n")
            append(summaryLine(ledger)).append('\n')
            ledger.newestFirst.forEach { append(line(it, zone)).append('\n') }
        }

    /** The Settings figure in milliseconds, for the header, with how many wakes it leaves out. */
    fun summaryLine(ledger: ConnectLedger): String {
        val times = ConnectStats.wakePictures(ledger)
        val leftOut = ledger.records.count(ConnectStats::leftOutOfFigure)
        val notCounted =
            " Not counted: $leftOut ${if (leftOut == 1) "wake" else "wakes"} with a picture whose screen wasn't in front within " +
                "${ConnectStats.FRONT_GRACE_MS} ms of the display on (marked below)."
        val s =
            ConnectStats.summary(times)
                ?: return "Display on to picture: not enough wakes with a picture yet (${times.size} of ${ConnectStats.MIN_COUNT}).$notCounted"
        return "Display on to picture, the last ${s.count} wakes with a picture: median ${s.medianMs} ms, 9 in 10 within ${s.ninetiethMs} ms " +
            "(nearest rank).$notCounted"
    }

    /** One connect on one line. */
    fun line(
        r: ConnectRecord,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }.format(Date(r.startedAtMs))
        val video = r.videoInMs?.let { ", video in ${at(it)}" }.orEmpty() + r.decoderReadyMs?.let { ", decoder ready ${at(it)}" }.orEmpty()
        val steps = "SUBSCRIBE ${at(r.subscribeMs)}$video, picture ${at(r.pictureMs)}, STREAMING ${at(r.streamingMs)}"
        val starts = if (r.starts == 1) "1 start" else "${r.starts} starts"
        val front =
            when (r.frontMs) {
                null -> "screen not in front"
                0L -> "screen in front from the origin"
                else -> "screen in front ${at(r.frontMs)}"
            }
        val asleep =
            when {
                r.origin != Origin.DISPLAY_ON || r.suspendedMs == null -> ""
                r.suspendedMs == 0L -> ", not suspended"
                else -> ", suspended ${r.suspendedMs} ms while off"
            }
        val adapter = r.adapter?.let(::adapterWords) ?: "no adapter line"
        return "$stamp ${r.kind.words}: ${outcomeWords(r)} | $steps | $starts, $front$asleep | $adapter | ${r.timeline ?: "no timeline"}"
    }

    private fun outcomeWords(r: ConnectRecord): String =
        when (r.outcome) {
            Outcome.PICTURE -> "picture ${at(r.endMs)}" + if (ConnectStats.leftOutOfFigure(r)) ", not counted: ${notCounted(r)}" else ""
            Outcome.NO_PHONE -> "no phone, no picture within ${ConnectTimer.WINDOW_MS / MS_PER_S} s"
            Outcome.ENDED_EARLY -> "ended early, ${r.endReason?.words ?: "unknown"}, ${at(r.endMs)}"
            Outcome.NOT_IN_FRONT -> "no picture within ${ConnectTimer.WINDOW_MS / MS_PER_S} s: Carlink's screen was not in front"
        }

    /** Why a wake's picture is left out of the figure ([ConnectStats.leftOutOfFigure]). */
    private fun notCounted(r: ConnectRecord): String = r.frontMs?.let { "Carlink's screen came to the front ${at(it)}" } ?: "Carlink's screen was not in front"

    private fun adapterWords(a: AdapterStartup): String {
        val up = a.uptimeMs?.let { "${it / MS_PER_S}.${it % MS_PER_S / MS_PER_TENTH} s" } ?: "-"
        val nudges = if (a.firstNudges == null && a.secondNudges == null) "-" else "${a.firstNudges ?: "-"}+${a.secondNudges ?: "-"}"
        val psk = a.pskMismatches?.let { if (a.pskAtLeast) ">=$it" else "$it" } ?: "-"
        return "adapter up $up, preload ${a.preload ?: "-"}, absent pages ${a.absentPages ?: "-"}, resets ${a.resets ?: "-"}, " +
            "nudges $nudges, PSK mismatches $psk"
    }

    private fun at(ms: Long?): String = ms?.let { "+$it ms" } ?: "-"
}
