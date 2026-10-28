package com.carlink.logging

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.carlink.ocbm.BoxLogEntry
import com.carlink.ocbm.Ocbm
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drive logging (Carlink2's file logging, extended to the box): while on, every INFO+ app line and
 * every line the box streams over CH_LOG is appended to ONE file in `Downloads/Carlink/`, in
 * arrival order, each with its time. Written once a second and synced to storage every few
 * seconds, so a drive's log survives the truck being switched off without anyone pressing Save Logs.
 *
 * One file per app start (`carlink3-drive-<start>.log`), a new part past [MAX_FILE_BYTES], and
 * files older than [RETENTION_DAYS] removed when recording starts. Lines are timestamped so a
 * moment called out on the road ("about 6:42, the cluster went blank") can be found afterwards.
 */
object DriveLog {
    private const val SUBDIR = "Carlink"
    private const val PREFIX = "carlink3-drive-"
    private const val MAX_FILE_BYTES = 20L * 1024 * 1024
    private const val RETENTION_DAYS = 7L
    private const val FLUSH_MS = 1000L
    private const val SYNC_EVERY_FLUSHES = 5
    private const val MAX_QUEUE = 20_000
    private const val SECONDS_PER_DAY = 86_400L

    @Volatile var recording = false
        private set

    /** Where the current file is, for the settings row ("Downloads/Carlink/…"). */
    @Volatile var currentFile: String? = null
        private set

    /** Newest box line written per source in this process; see [BoxReplayFilter]. */
    private val newestBoxMs = HashMap<Int, Long>()
    private val queue = ConcurrentLinkedQueue<String>()
    private val queued = AtomicInteger()
    private val dropped = AtomicInteger()
    private val boxTime = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val nameTime = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private val writer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "DriveLog").apply { isDaemon = true } }
    private var ticker: ScheduledFuture<*>? = null
    private var appContext: Context? = null
    private var sink: Sink? = null
    private var part = 0
    private var started = ""
    private var flushes = 0

    /** An open file: the stream, the descriptor that owns it (MediaStore), and its size so far. */
    private class Sink(
        val out: FileOutputStream,
        val pfd: ParcelFileDescriptor?,
        val label: String,
        var bytes: Long = 0,
    )

    @Synchronized
    fun start(context: Context) {
        if (recording) return
        appContext = context.applicationContext
        val stamp = synchronized(nameTime) { nameTime.format(Date()) }
        recording = true
        // One writer thread for the life of the process: a quick off/on runs close-then-open in order.
        writer.execute {
            started = stamp
            part = 0
            pruneOld()
            openNext()
        }
        ticker = writer.scheduleWithFixedDelay(::flushQueue, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop() {
        if (!recording) return
        recording = false
        ticker?.cancel(false)
        ticker = null
        writer.execute {
            flushQueue()
            closeSink()
            currentFile = null
        }
    }

    /** An app line, already stamped by [LogTrail] ("MM-dd HH:mm:ss.SSS I [TAG] …"). */
    fun appLine(line: String) {
        if (recording) offer(line)
    }

    /**
     * Box lines, stamped with the box's own clock and tagged `[box/<source>]`. The box replays its
     * log (flagged backfill) every time the stream is armed, so a reconnect would repeat the day's
     * history; replayed lines no newer than what this file already holds for that source are skipped.
     */
    fun boxEntries(entries: List<BoxLogEntry>) {
        if (!recording) return
        for (e in entries) {
            if (!BoxReplayFilter.keep(newestBoxMs, e)) continue
            val time = synchronized(boxTime) { boxTime.format(Date(e.unixMs)) }
            val replay = if (e.flags and Ocbm.LOG_F_BACKFILL != 0) " (replay)" else ""
            offer("$time B [box/${e.sourceName}]$replay ${e.text}")
        }
    }

    private fun offer(line: String) {
        if (queued.get() >= MAX_QUEUE) {
            dropped.incrementAndGet()
            return
        }
        queue.add(LogRedactor.line(line))
        queued.incrementAndGet()
    }

    private fun flushQueue() {
        val s = sink ?: return
        val lost = dropped.getAndSet(0)
        val text = StringBuilder()
        if (lost > 0) text.append("… drive log dropped $lost line(s): writer fell behind …\n")
        while (true) {
            val line = queue.poll() ?: break
            queued.decrementAndGet()
            text.append(line).append('\n')
        }
        if (text.isEmpty()) return
        val bytes = text.toString().toByteArray(Charsets.UTF_8)
        runCatching {
            s.out.write(bytes)
            s.out.flush()
            s.bytes += bytes.size
            if (++flushes % SYNC_EVERY_FLUSHES == 0) s.out.fd.sync()
        }.onFailure { android.util.Log.w("CARLINK", "[DRIVE_LOG] write failed: ${it.message}") }
        if (s.bytes > MAX_FILE_BYTES) {
            closeSink()
            openNext()
        }
    }

    private fun openNext() {
        val ctx = appContext ?: return
        part++
        val name = "$PREFIX$started" + (if (part > 1) "-part$part" else "") + ".log"
        val opened = runCatching { openInDownloads(ctx, name) }.getOrNull() ?: runCatching { openInAppFiles(ctx, name) }.getOrNull()
        sink = opened
        currentFile = opened?.label
        opened?.let {
            val header =
                "==== Carlink3 drive log ${it.label} (started $started, part $part) ====\n" +
                    "==== lines: app (I/W/E) and box ([box/<source>], box clock) ====\n"
            runCatching { it.out.write(header.toByteArray(Charsets.UTF_8)) }
        }
    }

    /** Visible in the head unit's Files app at once (no IS_PENDING), appended to as the drive goes. */
    private fun openInDownloads(
        ctx: Context,
        name: String,
    ): Sink {
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
            }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused the insert")
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "wa") ?: error("MediaStore gave no descriptor")
        return Sink(FileOutputStream(pfd.fileDescriptor), pfd, "Downloads/$SUBDIR/$name")
    }

    /** Fallback when the media provider refuses: this app's own files (adb-reachable). */
    private fun openInAppFiles(
        ctx: Context,
        name: String,
    ): Sink {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs").apply { mkdirs() }
        val file = File(dir, name)
        return Sink(FileOutputStream(file, true), null, file.absolutePath)
    }

    private fun closeSink() {
        val s = sink ?: return
        sink = null
        runCatching {
            s.out.flush()
            s.out.fd.sync()
            s.out.close()
        }
        runCatching { s.pfd?.close() }
    }

    /** Remove this app's drive logs older than [RETENTION_DAYS], matched by name, so also those left in Downloads/Carlink3 before 1.12. */
    private fun pruneOld() {
        val ctx = appContext ?: return
        val cutoff = System.currentTimeMillis() / 1000 - RETENTION_DAYS * SECONDS_PER_DAY
        runCatching {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.DATE_ADDED} < ?"
            resolver.query(collection, arrayOf(MediaStore.Downloads._ID), selection, arrayOf("$PREFIX%", cutoff.toString()), null)?.use { c ->
                while (c.moveToNext()) resolver.delete(ContentUris.withAppendedId(collection, c.getLong(0)), null, null)
            }
        }
    }
}

/**
 * Keeps a reconnect from re-writing the box's history: live lines always pass; a replayed
 * ([Ocbm.LOG_F_BACKFILL]) line passes only if it is newer than the newest line already kept from
 * its source. Updates [newest] as it goes. Called from the transport read thread only.
 */
internal object BoxReplayFilter {
    fun keep(
        newest: MutableMap<Int, Long>,
        e: BoxLogEntry,
    ): Boolean {
        val seen = newest[e.source] ?: Long.MIN_VALUE
        if (e.flags and Ocbm.LOG_F_BACKFILL != 0 && e.unixMs <= seen) return false
        if (e.unixMs > seen) newest[e.source] = e.unixMs
        return true
    }
}
