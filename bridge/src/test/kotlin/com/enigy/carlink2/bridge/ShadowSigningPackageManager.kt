package com.enigy.carlink2.bridge

import android.content.pm.PackageManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Robolectric's package manager has no hasSigningCertificate(uid, ...), which the caller pin asks. This one
 * answers from [signers]: a uid and the SHA-256 of the certificate its app is signed with; by package, from
 * [packageSigners], which also stands in for the package's signing info ([PackageFacts.signer]). Tests clear them.
 */
@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
class ShadowSigningPackageManager : ShadowApplicationPackageManager() {
    @Implementation
    protected fun hasSigningCertificate(
        uid: Int,
        certificate: ByteArray,
        type: Int,
    ): Boolean = type == PackageManager.CERT_INPUT_SHA256 && signers[uid]?.contentEquals(certificate) == true

    @Implementation
    protected fun hasSigningCertificate(
        packageName: String,
        certificate: ByteArray,
        type: Int,
    ): Boolean = type == PackageManager.CERT_INPUT_SHA256 && packageSigners[packageName]?.contentEquals(certificate) == true

    companion object {
        val signers = ConcurrentHashMap<Int, ByteArray>()
        val packageSigners = ConcurrentHashMap<String, ByteArray>()

        /** "AA:BB:…" as bytes, as `bridge/build.gradle.kts` writes the pins. */
        fun sha256(fingerprint: String): ByteArray = fingerprint.split(':').map { it.toInt(16).toByte() }.toByteArray()
    }
}
