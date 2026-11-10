package com.enigy.carlink2.bridge

import org.json.JSONException
import org.json.JSONObject

/**
 * API 7: the update Carlink asks for (ICarlinkBridge.installUpdate), the bridge it pins: [version] and [versionCode],
 * where to get it ([url]), and the [size] and [sha256] the file must have. [parse] refuses a request before anything is
 * fetched: a malformed one, a link that isn't a GitHub release download of enigy/carlink, or a version that isn't newer
 * than the installed bridge.
 */
internal data class UpdateRequest(
    val version: String,
    val versionCode: Long,
    val url: String,
    val size: Long,
    val sha256: String,
) {
    /** A request [parse] took, or why it refused. */
    sealed interface Parsed {
        data class Ok(
            val request: UpdateRequest,
        ) : Parsed

        data class Refused(
            val reason: String,
        ) : Parsed
    }

    companion object {
        /** The largest update the bridge takes; today's bridge is under 3 MB. */
        const val MAX_SIZE = 64L * 1024 * 1024

        private val SHA256 = Regex("[0-9a-fA-F]{64}")

        /** [json] checked against the allowed link [prefixes] and the installed bridge's [installedVersionCode]. */
        fun parse(
            json: String?,
            prefixes: List<String>,
            installedVersionCode: Long,
        ): Parsed {
            val request =
                try {
                    val o = JSONObject(json ?: "")
                    UpdateRequest(
                        version = o.getString(BridgeContract.UPDATE_VERSION),
                        versionCode = o.getLong(BridgeContract.UPDATE_VERSION_CODE),
                        url = o.getString(BridgeContract.UPDATE_URL),
                        size = o.getLong(BridgeContract.UPDATE_SIZE),
                        sha256 = o.getString(BridgeContract.UPDATE_SHA256).lowercase(),
                    )
                } catch (e: JSONException) {
                    return Parsed.Refused("The update request isn't readable: ${e.message}")
                }
            val refusal = request.refusal(prefixes, installedVersionCode)
            return if (refusal == null) Parsed.Ok(request) else Parsed.Refused(refusal)
        }
    }

    private fun refusal(
        prefixes: List<String>,
        installedVersionCode: Long,
    ): String? =
        when {
            prefixes.none { url.startsWith(it) } -> "The update link isn't a GitHub release download of enigy/carlink: $url"
            size !in 1..MAX_SIZE -> "The update's size ($size bytes) isn't one the bridge takes"
            !SHA256.matches(sha256) -> "The update's SHA-256 isn't 64 hex digits"
            version.isBlank() -> "The update names no version"
            versionCode <= installedVersionCode -> "Bridge $version (version code $versionCode) isn't newer than the installed one ($installedVersionCode)"
            else -> null
        }
}
