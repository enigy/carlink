package com.carlink.convert

import android.content.Context
import com.carlink.BuildConfig
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The installer image an app release converts with: pinned, so a download is checked before a byte goes to the adapter. */
data class PinnedImage(
    /** The enigy/ocbm release tag it was published under. */
    val tag: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * The conversion's installer image, `A15W_Update.img` (decisions of 10-28): downloaded from a public
 * enigy/ocbm release when the driver converts, and checked against [PINNED], so a new image means an
 * app release that pins it. Before the image is published, a debug build reads a copy from the head
 * unit instead: `ocbm/A15W_Update.img` in the app's external files folder, with
 * `A15W_Update.img.sha256` beside it, checked the same way.
 */
object InstallerImage {
    /**
     * The published image this release converts with; null until it is published (M7, which needs the owner's OK).
     * Publishing, as agreed with the Mac bench (10-28): turn on release immutability on enigy/ocbm before the first
     * release; create the release as a draft, attach the image and its SHA-256, then publish; then pin its tag,
     * asset URL, size and SHA-256 here, in a commit and an app release. Pin an image built from enigy/ocbm
     * 8d772cb or newer: from there a version undone with Go back installs again over USB with no OCBM_RETRY,
     * so Convert works again with the same image.
     */
    val PINNED: PinnedImage? = null

    /** Where the one-page stick guide is, once it is published with the image (M7); a build with the switch off points at it. */
    val STICK_GUIDE: String? = null

    const val FILE_NAME = "A15W_Update.img"

    /**
     * The SendFile name that starts the stock updater — a bare basename, not an absolute path.
     *
     * ARMadb-driver stages every SendFile upload in `/tmp` and, when the name holds "Update.img", runs
     * `/script/update_box_ota.sh <that name> &`. `update_box_ota.sh` tests `/tmp/$1`, so it prepends
     * `/tmp/` to the name it is given (lvalen's firmware_update.md). On the bench (28 Oct) an absolute
     * `/tmp/A15W_Update.img` landed in `/tmp` but the updater then looked for `/tmp//tmp/A15W_Update.img`
     * and stopped, changing nothing. The bare name makes both the staged file and the updater's `/tmp/$1`
     * resolve to `/tmp/A15W_Update.img`. The bench is still to confirm the install starts with it.
     */
    const val SEND_PATH = FILE_NAME

    /** The first 64-hex-digit word of a `.sha256` file, lower-cased; null when there is none. */
    fun hashIn(sidecar: String): String? =
        sidecar
            .split(Regex("\\s+"))
            .firstOrNull { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?.lowercase()

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** [bytes] when their SHA-256 is [sha256] (and, if given, their size [size]); a failure that says why otherwise. */
    fun checked(
        bytes: ByteArray,
        sha256: String,
        size: Long? = null,
    ): Result<ByteArray> =
        when {
            size != null && bytes.size.toLong() != size -> Result.failure(IOException("The installer is ${bytes.size} bytes, not the $size expected."))
            sha256Hex(bytes) != sha256.lowercase() -> Result.failure(IOException("The installer's SHA-256 does not match. Nothing was sent."))
            else -> Result.success(bytes)
        }
}

/** Where [StockConversion] gets the installer: [available] says whether it can, [obtain] gets it, checked. */
interface ImageSupplier {
    /** The folder a bench copy is read from (debug builds), for the screen that asks for one; null otherwise. */
    val benchFolder: String?

    fun available(): Boolean

    fun obtain(progress: (done: Long, total: Long) -> Unit): Result<ByteArray>
}

/**
 * The installer from the bench copy (debug builds, [bench]) or the pinned download, cached by its hash in
 * the app's cache so a retry does not download it again.
 */
class InstallerImageStore(
    private val context: Context,
    private val pinned: PinnedImage? = InstallerImage.PINNED,
    private val bench: Boolean = BuildConfig.DEBUG,
) : ImageSupplier {
    // Created on first use, so `adb push` has a folder to land in.
    private val benchDir: File? by lazy {
        if (bench) context.getExternalFilesDir(null)?.let { File(it, "ocbm").apply { mkdirs() } } else null
    }

    override val benchFolder: String? get() = benchDir?.path

    private fun benchFile(): File? = benchDir?.let { File(it, InstallerImage.FILE_NAME) }?.takeIf { it.isFile }

    override fun available(): Boolean = benchFile() != null || pinned != null

    override fun obtain(progress: (done: Long, total: Long) -> Unit): Result<ByteArray> =
        try {
            benchFile()?.let { fromBench(it) } ?: pinned?.let { fromRelease(it, progress) }
                ?: Result.failure(IOException("This build has no installer image."))
        } catch (e: IOException) {
            Result.failure(IOException("Could not get the installer: ${e.message}", e))
        }

    private fun fromBench(file: File): Result<ByteArray> {
        val sidecar = File(file.path + ".sha256")
        val want =
            sidecar.takeIf { it.isFile }?.let { InstallerImage.hashIn(it.readText()) }
                ?: return Result.failure(IOException("The bench copy has no ${sidecar.name} beside it."))
        logInfo("[CONVERT] installer from the bench copy ${file.path}", tag = Logger.Tags.ADAPTR)
        return InstallerImage.checked(file.readBytes(), want)
    }

    private fun fromRelease(
        p: PinnedImage,
        progress: (Long, Long) -> Unit,
    ): Result<ByteArray> {
        val cached = File(context.cacheDir, "ocbm/${p.sha256}.img")
        if (cached.isFile) {
            InstallerImage.checked(cached.readBytes(), p.sha256, p.sizeBytes).onSuccess { return Result.success(it) }
            cached.delete()
        }
        logInfo("[CONVERT] downloading installer ${p.tag} from ${p.url}", tag = Logger.Tags.ADAPTR)
        val bytes = download(p, progress)
        return InstallerImage.checked(bytes, p.sha256, p.sizeBytes).onSuccess {
            cached.parentFile?.mkdirs()
            runCatching { cached.writeBytes(it) }
        }
    }

    private fun download(
        p: PinnedImage,
        progress: (Long, Long) -> Unit,
    ): ByteArray {
        val conn = URL(p.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Carlink/${BuildConfig.VERSION_NAME} (https://github.com/enigy/carlink)")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code from the image's release")
            return conn.inputStream.use { readCapped(it, p.sizeBytes, progress) }
        } finally {
            conn.disconnect()
        }
    }

    /** All of [input], refusing more than [cap] bytes (the pinned size), reporting progress as it goes. */
    private fun readCapped(
        input: InputStream,
        cap: Long,
        progress: (Long, Long) -> Unit,
    ): ByteArray {
        val out = ByteArrayOutputStream(cap.toInt())
        val buf = ByteArray(READ_BUF)
        var n = input.read(buf)
        while (n >= 0) {
            if (out.size() + n > cap) throw IOException("the download is larger than the pinned image")
            out.write(buf, 0, n)
            progress(out.size().toLong(), cap)
            n = input.read(buf)
        }
        return out.toByteArray()
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
        const val READ_BUF = 64 * 1024
    }
}
