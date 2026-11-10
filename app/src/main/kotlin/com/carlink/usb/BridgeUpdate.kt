package com.carlink.usb

import android.content.Context
import com.carlink.BuildConfig
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.enigy.carlink2.bridge.BridgeContract
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * The USB bridge this Carlink pins, the way it pins OCBM ([com.carlink.convert.InstallerImage],
 * [com.carlink.ocbm.AdapterUpdates]): its version, where its APK is, and the size and SHA-256 it must have. The bridge
 * downloads and checks it itself (bridge API 7, ICarlinkBridge.installUpdate); Carlink never touches the APK.
 */
data class PinnedBridge(
    val versionName: String,
    val versionCode: Long,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    /** installUpdate's request. */
    fun json(): String =
        JSONObject()
            .put(BridgeContract.UPDATE_VERSION, versionName)
            .put(BridgeContract.UPDATE_VERSION_CODE, versionCode)
            .put(BridgeContract.UPDATE_URL, url)
            .put(BridgeContract.UPDATE_SIZE, sizeBytes)
            .put(BridgeContract.UPDATE_SHA256, sha256)
            .toString()

    /** The release page the APK is on, for the manual route: the link's tag. */
    val releasePage: String
        get() = url.removePrefix(BridgeContract.UPDATE_URL_PREFIX).substringBefore('/').let { "github.com/enigy/carlink/releases/tag/$it" }

    /** The APK's file name, as it is attached to the release. */
    val fileName: String get() = url.substringAfterLast('/')
}

object BridgePackage {
    /**
     * The bridge this release offers; null until a release pins one. Set when Carlink is released: the SIGNED
     * carlink_bridge APK committed as app/carlink_bridge.apk, its size and SHA-256 (as the README gives them), and its
     * link on that Carlink release, which attaches the same file because the bridge source is unchanged since.
     */
    val PINNED: PinnedBridge? =
        PinnedBridge(
            // Signed with the upload key by Release run 42874675260 (bridge-self-update 9769be5c, 10 Nov); v224 attaches
            // this file, app/carlink_bridge.apk, as is.
            versionName = "1.0.9",
            versionCode = 10,
            url = "https://github.com/enigy/carlink/releases/download/v224/carlink_bridge-1.0.9.apk",
            sizeBytes = 2_356_746L,
            sha256 = "3b9bbd18466da0d3b894a9914e55d7606b8fb7bf272f6680061b108122a4c6ee",
        )

    /** A debug build's bench pin: `bridge-pin.json` in the app's external files folder (`adb push`), the same keys as [PinnedBridge.json]. */
    private const val BENCH_FILE = "bridge-pin.json"

    /** The pin in force: [PINNED], or in a debug build a bench pin when one is pushed. */
    fun pinned(context: Context): PinnedBridge? = if (BuildConfig.DEBUG) bench(context) ?: PINNED else PINNED

    /** The installed bridge's version code, or null when it isn't installed. */
    fun installedVersionCode(context: Context): Long? = runCatching { context.packageManager.getPackageInfo(BridgeContract.BRIDGE_PACKAGE, 0).longVersionCode }.getOrNull()

    private fun bench(context: Context): PinnedBridge? {
        val file = context.getExternalFilesDir(null)?.let { File(it, BENCH_FILE) }?.takeIf { it.isFile } ?: return null
        return try {
            val o = JSONObject(file.readText())
            PinnedBridge(
                versionName = o.getString(BridgeContract.UPDATE_VERSION),
                versionCode = o.getLong(BridgeContract.UPDATE_VERSION_CODE),
                url = o.getString(BridgeContract.UPDATE_URL),
                sizeBytes = o.getLong(BridgeContract.UPDATE_SIZE),
                sha256 = o.getString(BridgeContract.UPDATE_SHA256),
            ).also { logInfo("[BRIDGE_UPDATE] bench pin: bridge ${it.versionName} (${it.versionCode}) from ${it.url}", tag = Logger.Tags.USB) }
        } catch (e: JSONException) {
            logWarn("[BRIDGE_UPDATE] bench pin $BENCH_FILE isn't readable: ${e.message}", tag = Logger.Tags.USB)
            null
        }
    }
}

/** What Settings offers for the bridge: nothing, the bridge's own update (API 7), or the manual route (older bridges). */
sealed interface BridgeUpdateOffer {
    data object None : BridgeUpdateOffer

    data class SelfUpdate(
        val pin: PinnedBridge,
    ) : BridgeUpdateOffer

    /** A bridge before API 7 (1.0.8 and older) can't update itself: a USB drive and Files, worded for an update. */
    data class Manual(
        val pin: PinnedBridge,
    ) : BridgeUpdateOffer

    companion object {
        /** The [pin] against the installed bridge: its version code, and the API it answers ([apiVersion], -1 unknown). */
        fun of(
            installedVersionCode: Long?,
            apiVersion: Int,
            pin: PinnedBridge?,
        ): BridgeUpdateOffer =
            when {
                pin == null || installedVersionCode == null || installedVersionCode >= pin.versionCode -> None
                apiVersion >= BridgeContract.UPDATE_API_VERSION -> SelfUpdate(pin)
                else -> Manual(pin)
            }
    }
}

/** Where a bridge update stands, as Settings shows it. */
sealed interface BridgeUpdateStep {
    /** Nothing running, or the last one's outcome read. */
    data object Idle : BridgeUpdateStep

    data object Asking : BridgeUpdateStep

    data object Downloading : BridgeUpdateStep

    data object Checking : BridgeUpdateStep

    data object Installing : BridgeUpdateStep

    /** The system's confirm screen is up, for the owner. */
    data object Confirming : BridgeUpdateStep

    /** The install ended the bridge's process: binding the new one. */
    data object Restarting : BridgeUpdateStep

    /** The head unit doesn't let the bridge install apps (yet). */
    data object NotAllowed : BridgeUpdateStep

    data class Updated(
        val version: String,
    ) : BridgeUpdateStep

    data class Failed(
        val message: String,
    ) : BridgeUpdateStep

    /** An update is under way: Update stays disabled. */
    val running: Boolean get() = this !is Idle && this !is NotAllowed && this !is Updated && this !is Failed

    companion object {
        /** The bridge's IBridgeUpdateListener.onState step. */
        fun ofState(state: String): BridgeUpdateStep =
            when (state) {
                BridgeContract.UPDATE_DOWNLOADING -> Downloading
                BridgeContract.UPDATE_CHECKING -> Checking
                else -> Installing
            }
    }
}
