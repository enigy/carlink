package com.carlink.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn

/**
 * Opens the head unit's built-in Files app (DocumentsUI). Car makers often hide it from the app
 * list but keep it installed, still allowed to hand APKs to the package installer, so it is how
 * the USB bridge gets installed from a USB drive ([210]).
 *
 * Same lookup as muzso/fileslauncher: the launch intent of either package name, else their known
 * activities. `com.android.documentsui` is AOSP's name; `com.google.android.documentsui` is the
 * Android Studio automotive emulator's. Needs the `<queries>` entries in the manifest. Files is
 * not distraction-optimized, so while driving the system covers it with its own blocking screen.
 */
object FilesLauncher {
    private val PACKAGES = listOf("com.android.documentsui", "com.google.android.documentsui")

    private val ACTIVITIES =
        listOf(
            "com.android.documentsui" to "com.android.documentsui.files.FilesActivity",
            "com.android.documentsui" to "com.android.documentsui.LauncherActivity",
            "com.google.android.documentsui" to "com.android.documentsui.LauncherActivity",
        )

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val DOWNLOADS_PROVIDER = "com.android.providers.downloads.documents"

    /** Starts Files and returns true, or returns false if no candidate could be started. */
    fun open(
        context: Context,
        logTag: String = "[BRIDGE_SETUP]",
    ): Boolean {
        val pm = context.packageManager
        val candidates =
            PACKAGES.mapNotNull { pm.getLaunchIntentForPackage(it) } +
                ACTIVITIES.map { (pkg, cls) -> Intent().setClassName(pkg, cls) }
        if (startFirst(context, candidates, logTag)) return true
        logWarn("$logTag No Files app could be opened (tried ${ACTIVITIES.size + PACKAGES.size} targets)", tag = Logger.Tags.USB)
        return false
    }

    /**
     * Files opened on the folder the logs are written to (`Downloads/<subdir>`), so the driver can
     * select them and Copy to a USB drive. DocumentsUI browses a directory from ACTION_VIEW with the
     * directory MIME type (Android 10+); failing that, the Downloads root; failing that, Files' home.
     */
    fun openFolder(
        context: Context,
        subdir: String,
        logTag: String,
    ): Boolean {
        val folder = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:${Environment.DIRECTORY_DOWNLOADS}/$subdir")
        val downloads = DocumentsContract.buildRootUri(DOWNLOADS_PROVIDER, "downloads")
        val views =
            PACKAGES.flatMap { pkg ->
                listOf(
                    Intent(Intent.ACTION_VIEW).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR).setPackage(pkg),
                    Intent(Intent.ACTION_VIEW).setDataAndType(downloads, DocumentsContract.Root.MIME_TYPE_ITEM).setPackage(pkg),
                )
            }
        return startFirst(context, views, logTag) || open(context, logTag)
    }

    private fun startFirst(
        context: Context,
        candidates: List<Intent>,
        logTag: String,
    ): Boolean {
        for (intent in candidates) {
            val target = intent.component?.flattenToShortString() ?: listOfNotNull(intent.`package`, intent.data).joinToString(" ")
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                logInfo("$logTag Opened Files ($target)", tag = Logger.Tags.USB)
                return true
            } catch (e: ActivityNotFoundException) {
                // Not on this head unit; try the next one.
            } catch (e: SecurityException) {
                logWarn("$logTag Files ($target) refused: ${e.message}", tag = Logger.Tags.USB)
            }
        }
        return false
    }
}
