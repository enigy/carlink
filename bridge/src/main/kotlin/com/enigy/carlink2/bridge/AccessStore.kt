package com.enigy.carlink2.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** An app the owner approved, by package and the SHA-256 of its signing certificate (AA:BB:…). */
internal data class Approval(
    val packageName: String,
    val label: String,
    val sha256: String,
    val installer: String?,
    val approvedAt: Long,
    val lastUsedAt: Long,
    val sessions: Int,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("package", packageName)
            .put("label", label)
            .put("sha256", sha256)
            .put("installer", installer ?: JSONObject.NULL)
            .put("approvedAt", approvedAt)
            .put("lastUsedAt", lastUsedAt)
            .put("sessions", sessions)

    companion object {
        fun fromJson(o: JSONObject) =
            Approval(
                o.getString("package"),
                o.optString("label", o.getString("package")),
                o.getString("sha256"),
                o.optStringOrNull("installer"),
                o.optLong("approvedAt"),
                o.optLong("lastUsedAt"),
                o.optInt("sessions"),
            )
    }
}

/**
 * An app that called the bridge while other apps' access was on and wasn't approved: what the owner sees under
 * "Asking for access", and the only thing an approval is taken from. One per package and key; [keyChanged] when
 * the package is approved, signed by another key.
 */
internal data class AccessRequest(
    val packageName: String,
    val label: String,
    val sha256: String,
    val installer: String?,
    val firstAt: Long,
    val lastAt: Long,
    val count: Int,
    val keyChanged: Boolean,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("package", packageName)
            .put("label", label)
            .put("sha256", sha256)
            .put("installer", installer ?: JSONObject.NULL)
            .put("firstAt", firstAt)
            .put("lastAt", lastAt)
            .put("count", count)
            .put("keyChanged", keyChanged)

    companion object {
        fun fromJson(o: JSONObject) =
            AccessRequest(
                o.getString("package"),
                o.optString("label", o.getString("package")),
                o.getString("sha256"),
                o.optStringOrNull("installer"),
                o.optLong("firstAt"),
                o.optLong("lastAt"),
                o.optInt("count"),
                o.optBoolean("keyChanged"),
            )
    }
}

/**
 * Other apps' access, kept in the bridge's own preferences (the bridge allows no backup): whether it is on, the
 * approvals and the requests. The handover is not kept here: it lives in memory, so it never outlives the bridge.
 */
internal class AccessStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var on: Boolean
        get() = prefs.getBoolean(KEY_ON, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ON, value).apply()
        }

    var approved: List<Approval>
        get() = read(KEY_APPROVED, Approval::fromJson)
        set(value) = write(KEY_APPROVED, value.map { it.toJson() })

    var requests: List<AccessRequest>
        get() = read(KEY_REQUESTS, AccessRequest::fromJson)
        set(value) = write(KEY_REQUESTS, value.map { it.toJson() })

    /** Off, and every approval and request forgotten. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun <T> read(
        key: String,
        parse: (JSONObject) -> T,
    ): List<T> =
        runCatching {
            val array = JSONArray(prefs.getString(key, "[]"))
            List(array.length()) { parse(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())

    private fun write(
        key: String,
        items: List<JSONObject>,
    ) {
        prefs.edit().putString(key, JSONArray(items).toString()).apply()
    }

    private companion object {
        const val PREFS = "bridge_access"
        const val KEY_ON = "on"
        const val KEY_APPROVED = "approved"
        const val KEY_REQUESTS = "requests"
    }
}

/** What the package manager says about an app that called: its label, signing certificate and installer. */
internal object PackageFacts {
    /**
     * The SHA-256 of [packageName]'s signing certificate. Tests replace it: Robolectric's package manager keeps
     * no signing certificates.
     */
    @Volatile var signer: (PackageManager, String) -> ByteArray? = ::signerFromPackageManager

    fun label(
        pm: PackageManager,
        packageName: String,
    ): String = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrNull()?.ifBlank { null } ?: packageName

    @Suppress("DEPRECATION") // getInstallerPackageName is the only call below API 30 (minSdk 29)
    fun installer(
        pm: PackageManager,
        packageName: String,
    ): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(packageName).installingPackageName
            } else {
                pm.getInstallerPackageName(packageName)
            }
        }.getOrNull()

    fun installed(
        pm: PackageManager,
        packageName: String,
    ): Boolean = runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess

    private fun signerFromPackageManager(
        pm: PackageManager,
        packageName: String,
    ): ByteArray? =
        runCatching {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
        }.getOrNull()
}

/** AA:BB:… for 32 bytes. */
internal fun fingerprint(sha256: ByteArray): String = sha256.joinToString(":") { "%02X".format(it) }

/** 64 hex digits, colons and spaces allowed, as bytes; null when it isn't that. */
internal fun parseFingerprint(text: String?): ByteArray? {
    val hex = text?.replace(":", "")?.replace(" ", "")?.trim() ?: return null
    if (hex.length != SHA256_HEX_DIGITS) return null
    return runCatching { ByteArray(SHA256_HEX_DIGITS / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
}

private const val SHA256_HEX_DIGITS = 64

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
