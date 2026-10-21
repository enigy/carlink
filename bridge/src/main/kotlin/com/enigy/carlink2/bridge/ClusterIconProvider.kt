package com.enigy.carlink2.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Claims `com.google.android.apps.automotive.templates.host.ClusterIconContentProvider`.
 *
 * Moved here from the display app's ClusterIconShimProvider (upstream lvalen91/carlink).
 * GoogleTemplatesHost has this provider class but never registers it. When the host turns a
 * CarIcon maneuver icon into navigation state for the cluster it calls insert() on this
 * authority; the first failure latches `skipIcons = true` for the session and the HUD shows no
 * icons. Play rejects the authority for anyone but its first claimant, so the Play-installed
 * display app cannot declare it — the sideloaded bridge does.
 *
 * Contract the host expects:
 * - insert(): store PNG bytes for an iconId, return a content URI
 * - query(selection = iconId): return contentUri + aspectRatio
 * - openFile(): serve the PNG via pipe (with optional ?w=&h= scaling)
 *
 * Content-addressed URIs ([201], bridge v2): the display app sends the same icon on every
 * distance tick, and the host re-inserts it each time. URIs used to be keyed by the host's
 * iconId, so if that id changes per insert the HUD gets a "new" image every tick and reloads
 * it — the flicker. URIs are now keyed by a digest of the PNG bytes, so an unchanged icon keeps
 * an unchanged URI and the URI only changes when the picture does (a new step).
 *
 * Exported with grantUriPermissions because the host and cluster run as other UIDs. Content
 * is limited to maneuver-icon PNGs the host itself inserted; delete/update are no-ops.
 *
 * Bounds (security review M1, bridge 1.0.4): only system apps (the Templates Host is one) may
 * insert or query, so no installed app can swap the cluster's turn arrows; inserts must be PNGs
 * of at most [MAX_PNG_BYTES]; openFile() scales only to 1..[MAX_SCALED_PX] and only sources up to
 * [MAX_SOURCE_PX], and an out-of-memory scale serves the original. Before, any app could make
 * this process run out of memory, and it owns the USB link, so CarPlay dropped with it.
 */
class ClusterIconProvider : ContentProvider() {
    // contentKey → PNG bytes. Access-order LRU; synchronizedMap so eviction runs under the lock.
    private val pngByContent: MutableMap<String, ByteArray> = lruMap(MAX_CONTENT_ENTRIES)

    // Host iconId → contentKey, for query(selection = iconId).
    private val contentByIconId: MutableMap<String, String> = lruMap(MAX_ICON_ID_ENTRIES)

    // "contentKey@WxH" → scaled PNG, so a HUD that re-opens the same URI every tick doesn't pay
    // a decode + scale + PNG encode each time.
    private val scaledPng: MutableMap<String, ByteArray> = lruMap(MAX_SCALED_ENTRIES)

    override fun onCreate(): Boolean {
        Log.i(TAG, "Cluster icon provider registered ($AUTHORITY)")
        return true
    }

    // Calling package -> whether it is a system app; a package can't change that without a reinstall.
    private val systemCallers = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // insert() must NEVER return null: null latches the host's skipIcons for the session.
    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri {
        val unknown = Uri.parse("content://$AUTHORITY/img/unknown")
        if (!callerIsSystem("insert")) return unknown
        val iconId = values?.getAsString("iconId")
        val data = values?.getAsByteArray("data")
        if (iconId == null || data == null) {
            Log.w(TAG, "insert() missing iconId or data (iconId=$iconId, dataSize=${data?.size})")
            return unknown
        }
        if (data.size > MAX_PNG_BYTES || !isPng(data)) {
            Log.w(TAG, "insert() refused: ${data.size} B, png=${isPng(data)}")
            return unknown
        }
        val contentKey = contentKey(data)
        BridgeStats.iconInserts.incrementAndGet()
        if (pngByContent.put(contentKey, data) == null) BridgeStats.iconDistinctContents.incrementAndGet()
        if (contentByIconId.put(iconId, contentKey) == null) BridgeStats.iconDistinctIds.incrementAndGet()
        return contentUri(contentKey)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("contentUri", "aspectRatio"))
        if (selection == null || !callerIsSystem("query")) return cursor

        val contentKey = contentByIconId[selection]
        val data = contentKey?.let { pngByContent[it] }
        if (contentKey == null || data == null) {
            BridgeStats.iconQueryMisses.incrementAndGet()
            return cursor
        }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        val aspectRatio = if (opts.outHeight > 0) opts.outWidth.toDouble() / opts.outHeight else 1.0
        cursor.addRow(arrayOf<Any>(contentUri(contentKey).toString(), aspectRatio))
        BridgeStats.iconQueryHits.incrementAndGet()
        return cursor
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val contentKey = uri.lastPathSegment
        val data = contentKey?.let { pngByContent[it] }
        if (contentKey == null || data == null) {
            BridgeStats.iconOpenMisses.incrementAndGet()
            Log.w(TAG, "openFile() cache miss for $contentKey")
            return null
        }

        val targetW = uri.getQueryParameter("w")?.toIntOrNull()
        val targetH = uri.getQueryParameter("h")?.toIntOrNull()
        val output =
            if (targetW != null && targetH != null && targetW in 1..MAX_SCALED_PX && targetH in 1..MAX_SCALED_PX) {
                val scaledKey = "$contentKey@${targetW}x$targetH"
                scaledPng[scaledKey] ?: scaleIcon(data, targetW, targetH).also { scaledPng[scaledKey] = it }
            } else {
                data
            }

        val pipe = ParcelFileDescriptor.createPipe()
        val writeEnd = pipe[1]
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { it.write(output) }
            } catch (e: Exception) {
                Log.e(TAG, "openFile() pipe write error for $contentKey: ${e.message}")
            }
        }, "IconPipe").start()
        BridgeStats.iconOpens.incrementAndGet()
        return pipe[0]
    }

    private fun contentUri(contentKey: String): Uri = Uri.parse("content://$AUTHORITY/img/$contentKey")

    /** "png_" + first 16 hex chars of SHA-256 — plenty to tell maneuver icons apart. */
    private fun contentKey(png: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(png)
        val hex = StringBuilder("png_")
        for (i in 0 until 8) hex.append("%02x".format(digest[i]))
        return hex.toString()
    }

    private fun scaleIcon(
        png: ByteArray,
        w: Int,
        h: Int,
    ): ByteArray =
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
            val original =
                if (bounds.outWidth in 1..MAX_SOURCE_PX && bounds.outHeight in 1..MAX_SOURCE_PX) {
                    BitmapFactory.decodeByteArray(png, 0, png.size)
                } else {
                    null
                }
            if (original == null) {
                png
            } else {
                val scaled = Bitmap.createScaledBitmap(original, w, h, true)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (scaled !== original) scaled.recycle()
                original.recycle()
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "scaleIcon(${w}x$h) failed: ${e.message} — serving original")
            png
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "scaleIcon(${w}x$h) ran out of memory — serving original")
            png
        }

    /**
     * Only system apps may write or look up icons: the Templates Host is one, and no app the
     * driver installs can be. Logged once per package, so a refused real host shows up in logcat.
     */
    private fun callerIsSystem(op: String): Boolean {
        val pkg = runCatching { callingPackage }.getOrNull() ?: return false.also { Log.w(TAG, "$op refused: no calling package") }
        return systemCallers.getOrPut(pkg) {
            val flags = runCatching { context?.packageManager?.getApplicationInfo(pkg, 0)?.flags }.getOrNull() ?: 0
            val system = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            Log.i(TAG, "$op from $pkg: ${if (system) "system app, allowed" else "not a system app, refused"}")
            system
        }
    }

    private fun isPng(data: ByteArray): Boolean = data.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { data[it] == PNG_SIGNATURE[it] }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun getType(uri: Uri): String = "image/png"

    private companion object {
        const val TAG = "Carlink2Bridge"
        const val AUTHORITY = BridgeContract.CLUSTER_ICON_AUTHORITY

        // Distinct maneuver pictures per trip are few; ~10-40 KB per PNG.
        const val MAX_CONTENT_ENTRIES = 20

        // The host may mint a new iconId per insert; ids are tiny, so keep a longer tail.
        const val MAX_ICON_ID_ENTRIES = 256
        const val MAX_SCALED_ENTRIES = 20

        /** Largest PNG insert() keeps; the display app's maneuver icons are 512 px, a few KB to ~60 KB. */
        const val MAX_PNG_BYTES = 512 * 1024

        /** Largest ?w=/?h= openFile() scales to; anything else gets the original bytes. */
        const val MAX_SCALED_PX = 1024

        /** Largest source openFile() will decode to scale. */
        const val MAX_SOURCE_PX = 2048

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        fun <V> lruMap(maxEntries: Int): MutableMap<String, V> =
            Collections.synchronizedMap(
                object : LinkedHashMap<String, V>(maxEntries, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > maxEntries
                },
            )
    }
}
