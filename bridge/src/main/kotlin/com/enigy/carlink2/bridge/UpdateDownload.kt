package com.enigy.carlink2.bridge

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches an [UpdateRequest]'s file into [file]: HTTPS only, from the request's link and through redirects to GitHub's
 * release asset hosts only ([BridgeContract.UPDATE_REDIRECT_HOSTS], or another allowed link [prefixes]), at most
 * [MAX_REDIRECTS] of them, and never more than twice the pinned size. [fetch] returns the bytes it got and their
 * SHA-256, or why it stopped.
 */
internal class UpdateDownload(
    private val net: UpdateParts.Net,
    private val prefixes: List<String>,
) {
    /** What [fetch] got, or why it stopped. */
    sealed interface Got {
        data class File(
            val bytes: Long,
            val sha256: String,
        ) : Got

        data class Failed(
            val reason: String,
        ) : Got
    }

    fun fetch(
        request: UpdateRequest,
        file: File,
    ): Got {
        var url =
            try {
                URL(request.url)
            } catch (e: MalformedURLException) {
                return Got.Failed("The update link isn't a URL: ${e.message}")
            }
        var hops = 0
        while (hops <= MAX_REDIRECTS) {
            val response =
                try {
                    net.get(url)
                } catch (e: IOException) {
                    return Got.Failed("The download from ${url.host} failed: ${e.message ?: e.javaClass.simpleName}")
                }
            when (val next = response.use { step(it, url, request, file) }) {
                is Step.Done -> return next.got
                is Step.Redirect -> url = next.to
            }
            hops++
        }
        return Got.Failed("The download was redirected more than $MAX_REDIRECTS times")
    }

    /** One response: a redirect to follow, or the outcome. */
    private sealed interface Step {
        data class Redirect(
            val to: URL,
        ) : Step

        data class Done(
            val got: Got,
        ) : Step
    }

    private fun step(
        response: UpdateParts.Response,
        from: URL,
        request: UpdateRequest,
        file: File,
    ): Step {
        if (response.code in REDIRECTS) {
            val to = response.location?.let { runCatching { URL(from, it) }.getOrNull() }
            return when {
                to == null -> Step.Done(Got.Failed("${from.host} redirected the download nowhere"))
                !allowed(to) -> Step.Done(Got.Failed("${from.host} redirected the download to ${to.protocol}://${to.host}, which the bridge doesn't fetch from"))
                else -> Step.Redirect(to)
            }
        }
        val body = response.body
        if (response.code != HTTP_OK || body == null) return Step.Done(Got.Failed("The download from ${from.host} answered HTTP ${response.code}"))
        return Step.Done(save(body, file, request.size * 2))
    }

    private fun allowed(url: URL): Boolean =
        url.protocol == "https" && (url.host in BridgeContract.UPDATE_REDIRECT_HOSTS || prefixes.any { url.toString().startsWith(it) })

    private fun save(
        body: InputStream,
        file: File,
        limit: Long,
    ): Got {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            file.outputStream().use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                var n = body.read(buffer)
                while (n >= 0 && total <= limit) {
                    total += n
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    n = body.read(buffer)
                }
            }
        } catch (e: IOException) {
            return Got.Failed("The download broke off after $total bytes: ${e.message ?: e.javaClass.simpleName}")
        }
        if (total > limit) return Got.Failed("The download ran past twice the pinned size; stopped at $total bytes")
        return Got.File(total, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val HTTP_OK = 200
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val BUFFER_BYTES = 64 * 1024
    }
}
