package com.carlink.ledger

import android.content.Context
import com.carlink.logging.Logger
import com.carlink.logging.logWarn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Where the connect ledger lives: one small JSON file ([ConnectLedgerCodec]) in Carlink's no-backup files folder, kept
 * across drives, never copied off the head unit except as text in Save Logs, and deleted with the app.
 *
 * Every read and write of the file runs on the store's own thread, never the caller's, so no disk work reaches the main
 * thread: [add] only queues, and the screen collects [ledger]. A write goes to a temp file, is synced, then renamed over
 * the last one, so a power cut (the head unit's normal shutdown) leaves the previous whole file. A file it can't read is
 * deleted and the ledger starts over: never a crash, and every task's failure is caught and reported through [warn].
 */
class ConnectLedgerStore(
    private val file: () -> File,
    private val warn: (String) -> Unit,
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "connect-ledger").apply { isDaemon = true } },
) {
    // Read and written on [io] only.
    private var current = ConnectLedger()

    private val state = MutableStateFlow<ConnectLedger?>(null)

    /** The ledger as last kept; null until the file has been read. */
    val ledger: StateFlow<ConnectLedger?> = state.asStateFlow()

    init {
        post {
            current = load()
            state.value = current
        }
    }

    /** Keep [record] as the newest connect, the oldest going past [ConnectLedger.CAP]. Returns at once. */
    fun add(record: ConnectRecord) =
        post {
            current = current.with(record)
            save()
            state.value = current
        }

    /** The ledger once the file is read and every write queued before is done. Blocking: never on the main thread. */
    fun snapshot(): ConnectLedger = runCatching { io.submit(Callable { current }).get() }.getOrElse { state.value ?: ConnectLedger() }

    /** Delete the ledger, file and all (the debug bench's ledger_clear). Blocking. Returns how many connects went. */
    fun clear(): Int =
        runCatching {
            io
                .submit(
                    Callable {
                        val gone = current.records.size
                        current = ConnectLedger()
                        file().delete()
                        state.value = current
                        gone
                    },
                ).get()
        }.getOrElse {
            warn("[LEDGER] not cleared: ${it.message}")
            0
        }

    /** Stop taking work, and let what is queued finish (tests). */
    fun close() {
        io.shutdown()
        runCatching { io.awaitTermination(CLOSE_WAIT_S, TimeUnit.SECONDS) }
    }

    private fun post(work: () -> Unit) {
        try {
            io.execute { runCatching(work).onFailure { warn("[LEDGER] ${it.javaClass.simpleName}: ${it.message}") } }
        } catch (e: RejectedExecutionException) {
            warn("[LEDGER] the store is closed: ${e.message}")
        }
    }

    private fun load(): ConnectLedger {
        val target = file()
        File(target.path + TMP).delete() // a write a power cut stopped short
        if (!target.exists()) return ConnectLedger()
        val read =
            try {
                if (target.length() > MAX_FILE_BYTES) null else ConnectLedgerCodec.decode(target.readText(Charsets.UTF_8))
            } catch (e: IOException) {
                warn("[LEDGER] ${target.name} could not be read: ${e.message}")
                null
            }
        if (read != null) return read
        target.delete()
        warn("[LEDGER] ${target.name} was not a ledger this Carlink can read: deleted, the ledger starts over")
        return ConnectLedger()
    }

    private fun save() {
        val target = file()
        val tmp = File(target.path + TMP)
        try {
            target.parentFile?.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(ConnectLedgerCodec.encode(current).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                warn("[LEDGER] ${target.name} not replaced: the newest connect is kept in memory only")
            }
        } catch (e: IOException) {
            tmp.delete()
            warn("[LEDGER] ${target.name} not written: ${e.message}")
        }
    }

    companion object {
        /** In the no-backup files folder. PERSISTENCE CONTRACT: renaming it forgets the ledger. */
        const val FILE_NAME = "connect_ledger.json"

        /** 200 connects take some 300 kB at the most; a file past this is not one the store wrote. */
        const val MAX_FILE_BYTES = 1_000_000L

        private const val TMP = ".tmp"
        private const val CLOSE_WAIT_S = 2L

        @Volatile private var instance: ConnectLedgerStore? = null

        /** The app's one store. Cheap: the file is first read on the store's own thread. */
        fun get(context: Context): ConnectLedgerStore =
            instance ?: synchronized(this) {
                instance ?: context.applicationContext
                    .let { app ->
                        ConnectLedgerStore(file = { File(app.noBackupFilesDir, FILE_NAME) }, warn = { logWarn(it, tag = Logger.Tags.ADAPTR) })
                    }.also { instance = it }
            }
    }
}
