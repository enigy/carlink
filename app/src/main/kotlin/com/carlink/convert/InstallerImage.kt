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

/** The installer image an app release converts with: pinned, so a download is checked before a byte goes to the stick. */
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
 * `A15W_Update.img.sha256` beside it, checked the same way. A head unit with no adb to put it there
 * (the truck) takes a debug build that carries the copy itself, packed into its assets at build time
 * (`-Pcarlink.benchImage`, app/build.gradle.kts) and checked the same way again.
 */
object InstallerImage {
    /**
     * The published image this release converts with; null until it is published (M7, which needs the owner's OK).
     * Publishing, as agreed with the Mac bench (10-28): turn on release immutability on enigy/ocbm before the first
     * release; create the release as a draft, attach the image and its SHA-256, then publish; then pin its tag,
     * asset URL, size and SHA-256 here, in a commit and an app release. The app writes it to a stick in the
     * adapter, where it runs as ROUTE=stick: pin an image built from enigy/ocbm 710eb23 or newer.
     */
    val PINNED: PinnedImage? = null

    /** Where the one-page stick guide is, once it is published with the image (M7); a build with the switch off points at it. */
    val STICK_GUIDE: String? = null

    /** The name the stock firmware installs from, at the root of a stick in the adapter. */
    const val FILE_NAME = "A15W_Update.img"

    /** The first 64-hex-digit word of a `.sha256` file, lower-cased; null when there is none. */
    fun hashIn(sidecar: String): String? =
        sidecar
            .split(Regex("\\s+"))
            .firstOrNull { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?.lowercase()

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** [bytes] when their SHA-256 is [sha256] (and, if given, their size [size]); a failure that says why otherwise, naming [what] they are. */
    fun checked(
        bytes: ByteArray,
        sha256: String,
        size: Long? = null,
        what: String = "installer",
    ): Result<ByteArray> =
        when {
            size != null && bytes.size.toLong() != size -> Result.failure(IOException("The $what is ${bytes.size} bytes, not the $size expected."))
            sha256Hex(bytes) != sha256.lowercase() -> Result.failure(IOException("The $what's SHA-256 does not match. Nothing was written."))
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
 * The installer from the bench copy (debug builds, [bench]), else the copy packed into a debug build
 * ([packed], the APK's assets), else the pinned download, cached by its hash in the app's cache so a
 * retry does not download it again. A release build reads neither copy.
 */
class InstallerImageStore(
    private val context: Context,
    private val pinned: PinnedImage? = InstallerImage.PINNED,
    private val bench: Boolean = BuildConfig.DEBUG,
    private val packed: (path: String) -> InputStream? = { path -> runCatching { context.assets.open(path) }.getOrNull() },
) : ImageSupplier {
    // Created on first use, so `adb push` has a folder to land in.
    private val benchDir: File? by lazy {
        if (bench) context.getExternalFilesDir(null)?.let { File(it, "ocbm").apply { mkdirs() } } else null
    }

    override val benchFolder: String? get() = benchDir?.path

    private fun benchFile(): File? = benchDir?.let { File(it, InstallerImage.FILE_NAME) }?.takeIf { it.isFile }

    override fun available(): Boolean = benchFile() != null || packedSidecar() != null || pinned != null

    override fun obtain(progress: (done: Long, total: Long) -> Unit): Result<ByteArray> =
        try {
            benchFile()?.let { fromBench(it) } ?: fromPacked() ?: pinned?.let { fromRelease(it, progress) }
                ?: Result.failure(IOException("This build has no installer image."))
        } catch (e: IOException) {
            Result.failure(IOException("Could not get the installer: ${e.message}", e))
        }

    /** The packed copy's SHA-256 file, in a debug build that carries one; null otherwise. */
    private fun packedSidecar(): String? = if (bench) packed("$PACKED_IMAGE.sha256")?.use { String(it.readBytes(), Charsets.UTF_8) } else null

    /** The copy packed into this debug build, checked against its SHA-256 file; null when the build carries none. */
    private fun fromPacked(): Result<ByteArray>? {
        val sidecar = packedSidecar() ?: return null
        val want = InstallerImage.hashIn(sidecar) ?: return Result.failure(IOException("The packed installer's SHA-256 file has no hash."))
        val bytes = packed(PACKED_IMAGE)?.use { it.readBytes() } ?: return Result.failure(IOException("The packed installer is missing."))
        logInfo("[CONVERT] installer packed in this build (assets $PACKED_IMAGE, ${bytes.size} B)", tag = Logger.Tags.ADAPTR)
        return InstallerImage.checked(bytes, want)
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
        val bytes = ReleaseDownload.fetch(p.url, p.sizeBytes, progress)
        return InstallerImage.checked(bytes, p.sha256, p.sizeBytes).onSuccess {
            cached.parentFile?.mkdirs()
            runCatching { cached.writeBytes(it) }
        }
    }

    private companion object {
        /** Where -Pcarlink.benchImage packs the copy in a debug APK's assets. */
        const val PACKED_IMAGE = "ocbm/${InstallerImage.FILE_NAME}"
    }
}

/** A pinned enigy/ocbm release asset, downloaded whole: the installer image, or an adapter update package. */
internal object ReleaseDownload {
    private const val TIMEOUT_MS = 20_000
    private const val READ_BUF = 64 * 1024

    /** All of [url], refusing more than [cap] bytes (the pinned size), reporting progress as it goes. */
    fun fetch(
        url: String,
        cap: Long,
        progress: (done: Long, total: Long) -> Unit,
    ): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Carlink/${BuildConfig.VERSION_NAME} (https://github.com/enigy/carlink)")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code from the release")
            return conn.inputStream.use { readCapped(it, cap, progress) }
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(
        input: InputStream,
        cap: Long,
        progress: (Long, Long) -> Unit,
    ): ByteArray {
        val out = ByteArrayOutputStream(cap.toInt())
        val buf = ByteArray(READ_BUF)
        var n = input.read(buf)
        while (n >= 0) {
            if (out.size() + n > cap) throw IOException("the download is larger than the pinned file")
            out.write(buf, 0, n)
            progress(out.size().toLong(), cap)
            n = input.read(buf)
        }
        return out.toByteArray()
    }
}
