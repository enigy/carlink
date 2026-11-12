package com.carlink.fuel

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * The Fuel card's prices: Google Places with the driver's own key ([GooglePlaces], [GooglePlacesStore]). Busy for 5xx
 * ([SourceBusy]); out of quota for 429 ([GoogleQuota]: usually the key's own daily cap, which won't clear in minutes); any
 * other refusal is the driver's to fix ([GoogleKeyProblem], in plain words). Each request
 * also names the app (package and signing certificate), so a key the driver restricted to Carlink works too.
 */
class GooglePlacesSource(
    private val key: () -> String?,
    private val appHeaders: Map<String, String> = emptyMap(),
    /** POSTs [GooglePlaces.body] with the headers to [GooglePlaces.ENDPOINT]. Swapped in tests. */
    private val post: (body: String, headers: Map<String, String>) -> HttpAnswer = { body, headers ->
        Http.post(GooglePlaces.ENDPOINT, headers, body.toByteArray(Charsets.UTF_8), READ_TIMEOUT_MS)
    },
) : FuelPriceSource {
    override val name = "Google Places"
    override val attribution = "Google Maps"

    override fun stationsNear(
        here: GeoPoint,
        radiusM: Double,
    ): List<Station> {
        val k = key() ?: throw GoogleKeyProblem("No Google key is set.")
        val headers =
            appHeaders +
                mapOf(
                    "Content-Type" to "application/json",
                    "X-Goog-Api-Key" to k,
                    "X-Goog-FieldMask" to GooglePlaces.FIELD_MASK,
                )
        val answer = post(GooglePlaces.body(here, radiusM), headers)
        if (answer.code == HTTP_OK) return GooglePlaces.parse(answer.body)
        throw failure(answer)
    }

    companion object {
        private const val READ_TIMEOUT_MS = 30_000
        private const val HTTP_OK = 200
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** A refusal: out of quota for 429, busy for 5xx ([httpFailure]), else Google's reason in the driver's words. */
        fun failure(answer: HttpAnswer): Exception {
            if (answer.code == HTTP_TOO_MANY_REQUESTS) return GoogleQuota("HTTP ${answer.code}")
            val http = httpFailure(answer.code, answer.retryAfter)
            return if (http is SourceBusy) http else GoogleKeyProblem(GooglePlaces.problem(answer.code, answer.body))
        }

        /** `X-Android-Package` and `X-Android-Cert` (the signing certificate's SHA-1), for a key restricted to Android apps. */
        fun appHeaders(context: Context): Map<String, String> {
            val pm = context.packageManager
            val info = runCatching { pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
            val signer = info?.signingInfo?.apkContentsSigners?.firstOrNull() ?: return mapOf("X-Android-Package" to context.packageName)
            val sha1 = MessageDigest.getInstance("SHA-1").digest(signer.toByteArray()).joinToString("") { "%02X".format(it) }
            return mapOf("X-Android-Package" to context.packageName, "X-Android-Cert" to sha1)
        }
    }
}
