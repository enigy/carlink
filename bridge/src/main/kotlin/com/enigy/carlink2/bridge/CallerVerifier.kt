package com.enigy.carlink2.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides whether a Binder caller may drive the adapter.
 *
 * An open bridge would let any app on the head unit talk to the CPC200, including pushing
 * binaries the adapter executes (SendFile to /tmp/bin). The caller must be
 * [BridgeContract.DISPLAY_PACKAGE]; when [certsConfig] lists fingerprints, it must also be
 * signed by one of them, or have been installed by the Play Store.
 *
 * The Play fallback is safe because Play installs only the real listing's app under that package
 * name, and it keeps a mistyped pin from locking the Play build out of the adapter. What the pin
 * stops is a differently signed `com.enigy.carlink3` sideloaded while the real one is absent
 * (security review M3). Every accepted caller's fingerprint is logged once.
 */
internal class CallerVerifier(
    private val context: Context,
    certsConfig: String,
) {
    private val allowedCerts: List<ByteArray> =
        certsConfig
            .split(',')
            .map { it.replace(":", "").replace(" ", "").trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull(::parseSha256)

    // Verification needs two PackageManager calls; write() runs per adapter message.
    private val verifiedUids = ConcurrentHashMap.newKeySet<Int>()

    init {
        if (allowedCerts.isEmpty()) {
            Log.w(TAG, "No caller certificate pinned — accepting ${BridgeContract.DISPLAY_PACKAGE} by package name")
        }
    }

    /** Null when allowed; otherwise why not. */
    fun rejectReason(uid: Int): String? {
        if (uid in verifiedUids) return null
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)?.toList().orEmpty()
        if (BridgeContract.DISPLAY_PACKAGE !in packages) {
            return "uid $uid ($packages) is not ${BridgeContract.DISPLAY_PACKAGE}"
        }
        val pinned = allowedCerts.isEmpty() || allowedCerts.any { pm.hasSigningCertificate(uid, it, PackageManager.CERT_INPUT_SHA256) }
        val fromPlay = !pinned && installer(pm) == PLAY_STORE
        if (!pinned && !fromPlay) {
            return "${BridgeContract.DISPLAY_PACKAGE} (uid $uid) is not signed by a pinned certificate (${signer(pm)}) and not from Play"
        }
        if (fromPlay) {
            Log.w(TAG, "Accepting ${BridgeContract.DISPLAY_PACKAGE} as Play-installed; its certificate ${signer(pm)} is not pinned")
        } else {
            Log.i(TAG, "Accepting ${BridgeContract.DISPLAY_PACKAGE} (uid $uid) signed by ${signer(pm)}")
        }
        verifiedUids += uid
        return null
    }

    @Suppress("DEPRECATION") // getInstallerPackageName is the only call below API 30 (minSdk 29)
    private fun installer(pm: PackageManager): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(BridgeContract.DISPLAY_PACKAGE).installingPackageName
            } else {
                pm.getInstallerPackageName(BridgeContract.DISPLAY_PACKAGE)
            }
        }.getOrNull()

    /** The display app's signing certificate SHA-256 as AA:BB:…, for the log; "unknown" if unreadable. */
    private fun signer(pm: PackageManager): String =
        runCatching {
            val info = pm.getPackageInfo(BridgeContract.DISPLAY_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return "unknown"
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
        }.getOrDefault("unknown")

    /** Package UIDs can be reassigned after an uninstall; re-verify after clients go away. */
    fun forget() {
        verifiedUids.clear()
    }

    private fun parseSha256(hex: String): ByteArray? {
        if (hex.length != 64) {
            Log.e(TAG, "Ignoring caller cert '$hex': expected 64 hex characters")
            return null
        }
        return try {
            ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Ignoring caller cert '$hex': ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "Carlink2Bridge"
        const val PLAY_STORE = "com.android.vending"
    }
}
