package com.carlink.logging

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Writes diagnostic files where the driver can get at them without adb.
 *
 * First choice is the shared Downloads collection (`Downloads/Carlink3/`), visible in the head
 * unit's Files app and to a USB stick copy; no permission is needed on API 29+. If the platform's
 * media provider refuses (some AAOS builds have no Downloads UI), the file goes to this app's own
 * external files directory instead, which `adb pull` can still reach.
 */
object LogExport {
    private const val SUBDIR = "Carlink3"

    /** @return a short human-readable location of the saved file. */
    fun save(
        context: Context,
        name: String,
        bytes: ByteArray,
    ): Result<String> =
        LogRedactor.text(bytes).let { clean ->
            runCatching { saveToDownloads(context, name, clean) }
                .recoverCatching { saveToAppFiles(context, name, clean) }
        }

    /** This process's own logcat (what the OS lets an app read without READ_LOGS), or null if unavailable. */
    fun appLogcat(): ByteArray? =
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
            val bytes = p.inputStream.use { it.readBytes() }
            p.waitFor()
            bytes.takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun saveToDownloads(
        context: Context,
        name: String,
        bytes: ByteArray,
    ): String {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused the insert")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("MediaStore gave no output stream")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Downloads/$SUBDIR/$name"
    }

    private fun saveToAppFiles(
        context: Context,
        name: String,
        bytes: ByteArray,
    ): String {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(bytes)
        return f.absolutePath
    }
}
