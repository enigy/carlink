package com.carlink.fuel

import com.carlink.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** One answer: its HTTP [code], its body (the error body for a failure), and `Retry-After` when sent. */
data class HttpAnswer(
    val code: Int,
    val body: String,
    val retryAfter: String? = null,
)

/**
 * The Fuel card's one HTTP call, a POST, for every source: no connection is [SourceUnreachable], a read that times out
 * is [SourceBusy]; any status comes back for the source to judge. Carlink names itself in the User-Agent, as Overpass
 * asks. Nothing here logs: the body carries the car's position, and Google's request the driver's key.
 */
internal object Http {
    private const val CONNECT_TIMEOUT_MS = 15_000
    const val USER_AGENT = "Carlink/${BuildConfig.VERSION_NAME} (Android Automotive; https://github.com/enigy/carlink)"

    fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        readTimeoutMs: Int,
    ): HttpAnswer {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            send(conn, body)
            return timed {
                val code = conn.responseCode
                val stream = if (code in OK_RANGE) conn.inputStream else conn.errorStream
                HttpAnswer(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty(), conn.getHeaderField("Retry-After"))
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Connect and send: any failure here is no connection. */
    private fun send(
        conn: HttpURLConnection,
        body: ByteArray,
    ) {
        try {
            conn.connect()
            conn.outputStream.use { it.write(body) }
        } catch (e: IOException) {
            throw SourceUnreachable(e.javaClass.simpleName, e)
        }
    }

    private inline fun <T> timed(block: () -> T): T =
        try {
            block()
        } catch (e: SocketTimeoutException) {
            throw SourceBusy("timeout", cause = e)
        }

    private val OK_RANGE = 200..299
}
