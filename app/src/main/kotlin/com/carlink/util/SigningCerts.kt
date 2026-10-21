package com.carlink.util

import android.content.pm.PackageManager
import java.security.MessageDigest

/** Signing certificate fingerprints, for the logs that check certificate pins. */
object SigningCerts {
    /** SHA-256 of [packageName]'s first signing certificate as "AA:BB:…", or "unknown" if unreadable. */
    fun sha256(
        pm: PackageManager,
        packageName: String,
    ): String =
        runCatching {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return "unknown"
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
        }.getOrDefault("unknown")
}
