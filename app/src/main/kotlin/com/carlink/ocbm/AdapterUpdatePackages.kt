package com.carlink.ocbm

import android.content.Context
import com.carlink.BuildConfig
import com.carlink.convert.InstallerImage
import com.carlink.convert.ReleaseDownload
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** The update package an app release offers: pinned, as the installer image is, so a download is checked before a byte goes to the adapter. */
data class PinnedPackage(
    /** The enigy/ocbm release tag it was published under. */
    val tag: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /** What the package says of itself, copied from its VERSION, FROM_MIN and ROOM files, and the notes for the driver. */
    val pkg: UpdatePackage,
)

object AdapterUpdates {
    /**
     * The published package this release offers; null until one is published (U4, which needs the owner's OK). Publishing,
     * as for the installer image: build it with enigy/ocbm tools/make_ocbm_update.sh --from the SHA256SUMS of the version
     * it updates from, attach it and its .sha256 to a draft release, publish, then pin its tag, asset URL, size, SHA-256
     * and the VERSION, FROM_MIN and ROOM need_kb it carries here, in a commit and an app release. Pin the installer image
     * of the same version with it: a trial adapter it doesn't fit takes the round trip, and converts with that image.
     */
    val PINNED: PinnedPackage? = null

    /** Where the adapter's update script takes the package from (ocbmd UPDATE_PACKAGE). */
    const val BOX_PATH = "/tmp/ocbm-update.tgz"

    /** A package's name: ocbm-update-1.1.0.tgz. */
    val FILE_NAME = Regex("""ocbm-update-[0-9A-Za-z.+-]+\.tgz""")
}

/** Reads what an update package (`ocbm-update-X.Y.Z.tgz`, a gzipped ustar archive) says of itself. */
object UpdatePackageFile {
    private const val BLOCK = 512
    private const val NAME_LEN = 100
    private const val SIZE_AT = 124
    private const val SIZE_LEN = 12
    private const val TYPE_AT = 156
    private const val PREFIX_AT = 345
    private const val PREFIX_LEN = 155
    private const val OCTAL = 8

    /** The most a metadata file may hold: they are one or two lines. */
    private const val META_MAX = 4096
    private val META = setOf("VERSION", "FROM_MIN", "ROOM")
    private const val NEED_KB = "need_kb="

    /** Its VERSION, FROM_MIN and ROOM need_kb; null when it is not an update package or is damaged. */
    fun read(bytes: ByteArray): UpdatePackage? =
        try {
            val meta = metadata(GZIPInputStream(ByteArrayInputStream(bytes)))
            val version = meta["VERSION"]?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
            val fromMin = meta["FROM_MIN"]?.trim()?.takeIf { it.isNotEmpty() }
            val room = meta["ROOM"].orEmpty().lines().map { it.trim() }
            val needKb = room.firstOrNull { it.startsWith(NEED_KB) }?.removePrefix(NEED_KB)?.toIntOrNull()
            if (version == null || fromMin == null || needKb == null) null else UpdatePackage(version, fromMin, needKb)
        } catch (e: IOException) {
            logWarn("[UPDATE] not a readable update package: ${e.message}", tag = Logger.Tags.ADAPTR)
            null
        }

    /** The archive's top-level VERSION, FROM_MIN and ROOM, by name. */
    private fun metadata(input: InputStream): Map<String, String> {
        val out = HashMap<String, String>()
        val header = ByteArray(BLOCK)
        while (true) {
            if (!readFully(input, header) || header.all { it.toInt() == 0 }) return out
            val name = field(header, PREFIX_AT, PREFIX_LEN).let { p -> if (p.isEmpty()) "" else "$p/" } + field(header, 0, NAME_LEN)
            val size = field(header, SIZE_AT, SIZE_LEN).trim().toLongOrNull(OCTAL) ?: throw IOException("entry $name has no size")
            val regular = header[TYPE_AT].toInt().toChar().let { it == '0' || it == '\u0000' }
            val padded = (size + BLOCK - 1) / BLOCK * BLOCK
            if (regular && name in META && size <= META_MAX) {
                val data = ByteArray(padded.toInt())
                if (!readFully(input, data)) throw EOFException("entry $name is cut short")
                out[name] = String(data, 0, size.toInt(), Charsets.UTF_8)
            } else {
                skipFully(input, padded)
            }
        }
    }

    private fun field(
        header: ByteArray,
        at: Int,
        len: Int,
    ): String {
        var end = at
        while (end < at + len && header[end].toInt() != 0) end++
        return String(header, at, end - at, Charsets.US_ASCII)
    }

    private fun readFully(
        input: InputStream,
        buf: ByteArray,
    ): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(
        input: InputStream,
        count: Long,
    ) {
        val buf = ByteArray(BLOCK)
        var left = count
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left, BLOCK.toLong()).toInt())
            if (n < 0) throw EOFException("the archive is cut short")
            left -= n
        }
    }
}

/** Where [AdapterUpdateSession] gets the package: [known] says which, without downloading it; [obtain] gets it, checked. */
interface UpdateSupplier {
    /** The newest package this build knows of; null when there is none. Reads files: call off the main thread. */
    fun known(): UpdatePackage?

    fun obtain(progress: (done: Long, total: Long) -> Unit): Result<ByteArray>
}

/**
 * The update package from the bench copy (debug builds, [bench]): the newest `ocbm-update-X.Y.Z.tgz` with its `.sha256`
 * beside it in `ocbm/` in the app's external files folder, where the installer image's bench copy goes; else the pinned
 * download, cached by its hash so a retry does not download it again. A release build reads no bench copy.
 */
class AdapterUpdateStore(
    private val context: Context,
    private val pinned: PinnedPackage? = AdapterUpdates.PINNED,
    private val bench: Boolean = BuildConfig.DEBUG,
) : UpdateSupplier {
    // Created on first use, so `adb push` has a folder to land in.
    private val benchDir: File? by lazy {
        if (bench) context.getExternalFilesDir(null)?.let { File(it, "ocbm").apply { mkdirs() } } else null
    }

    // The bench copies read so far, by path, size and time, so a session start does not read them all again.
    private val read = HashMap<String, Pair<String, UpdatePackage?>>()

    override fun known(): UpdatePackage? = newestBench()?.second ?: pinned?.pkg

    override fun obtain(progress: (done: Long, total: Long) -> Unit): Result<ByteArray> =
        try {
            newestBench()?.let { (file, _) -> fromBench(file) } ?: pinned?.let { fromRelease(it, progress) }
                ?: Result.failure(IOException("This build has no adapter update."))
        } catch (e: IOException) {
            Result.failure(IOException("Could not get the update: ${e.message}", e))
        }

    /** The bench copy with the newest version, and what it says of itself. */
    private fun newestBench(): Pair<File, UpdatePackage>? =
        benchDir
            ?.listFiles { f -> f.isFile && AdapterUpdates.FILE_NAME.matches(f.name) }
            ?.mapNotNull { f -> readBench(f)?.let { f to it } }
            ?.maxWithOrNull { a, b -> OcbmVersion.key(a.second.version).compareTo(OcbmVersion.key(b.second.version)) }

    private fun readBench(file: File): UpdatePackage? {
        val stamp = "${file.length()}:${file.lastModified()}"
        synchronized(read) { read[file.path]?.takeIf { it.first == stamp }?.let { return it.second } }
        val pkg = UpdatePackageFile.read(file.readBytes())
        synchronized(read) { read[file.path] = stamp to pkg }
        return pkg
    }

    private fun fromBench(file: File): Result<ByteArray> {
        val sidecar = File(file.path + ".sha256")
        val want =
            sidecar.takeIf { it.isFile }?.let { InstallerImage.hashIn(it.readText()) }
                ?: return Result.failure(IOException("The bench copy has no ${sidecar.name} beside it."))
        logInfo("[UPDATE] package from the bench copy ${file.path}", tag = Logger.Tags.ADAPTR)
        return InstallerImage.checked(file.readBytes(), want, what = PACKAGE)
    }

    private fun fromRelease(
        p: PinnedPackage,
        progress: (Long, Long) -> Unit,
    ): Result<ByteArray> {
        val cached = File(context.cacheDir, "ocbm/${p.sha256}.tgz")
        if (cached.isFile) {
            InstallerImage.checked(cached.readBytes(), p.sha256, p.sizeBytes, PACKAGE).onSuccess { return Result.success(it) }
            cached.delete()
        }
        logInfo("[UPDATE] downloading the update ${p.tag} from ${p.url}", tag = Logger.Tags.ADAPTR)
        val bytes = ReleaseDownload.fetch(p.url, p.sizeBytes, progress)
        return InstallerImage.checked(bytes, p.sha256, p.sizeBytes, PACKAGE).onSuccess {
            cached.parentFile?.mkdirs()
            runCatching { cached.writeBytes(it) }
        }
    }

    private companion object {
        const val PACKAGE = "update package"
    }
}
