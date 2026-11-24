package com.carlink.ocbm

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * How a Wi-Fi link ended, for the session's error line in the drive log ([WifiBulkTransport.howItEnded]): which side ended
 * it, why, and what the link had carried until then.
 *
 * WHY. On the 24 Nov bench (12:22) the drive log said only "link dead: read loop died" for a link the box saw closed by
 * its host 31.9 s after an attach. The transport knew why it ended but said so to logcat alone, so which side closed
 * first could not be told from either log.
 *
 * "The adapter's side" is whatever holds the far end of Carlink's TCP connection: the box on the phone's hotspot, or on
 * the bench the emulator's NAT that stands between them. An end of stream or a reset comes from there; Carlink's own
 * rules (the dead-link rule, a stuck write, a refused record) end the link from here.
 */
class WifiLinkEnd private constructor(
    val by: By,
    val why: String,
) {
    /** Who ended the link. */
    enum class By(
        val words: String,
    ) {
        /** The far end closed or reset the TCP connection. */
        ADAPTER("closed by the adapter's side"),

        /** One of Carlink's own rules, or its own failure. */
        CARLINK("closed by Carlink"),

        /** The socket failed under Carlink in another way: the head unit's network stack says how. */
        CONNECTION("the connection failed under Carlink"),
    }

    override fun toString(): String = "${by.words}: $why"

    /** What the link carried, said after the reason: which way went quiet first, and when. */
    data class Stats(
        val linkedMs: Long,
        val recordsIn: Long,
        val keepalivesIn: Long,
        val bytesIn: Long,
        /** Since the last record from the adapter, or null for none at all. */
        val lastInMsAgo: Long?,
        val bytesOut: Long,
        val keepalivesOut: Long,
        /** Since the last record Carlink wrote, or null for none at all. */
        val lastOutMsAgo: Long?,
    ) {
        override fun toString(): String {
            val inPart = lastInMsAgo?.let { "in $recordsIn records ($keepalivesIn keepalives) $bytesIn B, the last $it ms before" } ?: "nothing in"
            val outPart = lastOutMsAgo?.let { "out $bytesOut B ($keepalivesOut keepalives), the last $it ms before" } ?: "nothing out"
            return "linked $linkedMs ms; $inPart; $outPart"
        }
    }

    /** The session's line: who, why, then [stats]. */
    fun line(stats: Stats): String = "$this; $stats"

    companion object {
        /** The stream ended between two records: the far end closed the connection. */
        fun endOfStream(): WifiLinkEnd = WifiLinkEnd(By.ADAPTER, "the TCP connection ended between records (end of stream)")

        /** The socket's read threw [e]; [deadMs] is the dead-link rule's time. */
        fun readFailed(
            e: IOException,
            deadMs: Int,
        ): WifiLinkEnd =
            when (e) {
                is SocketTimeoutException -> WifiLinkEnd(By.CARLINK, "no bytes from the adapter for $deadMs ms (the dead-link rule)")
                is OcbwLink.LinkException -> WifiLinkEnd(By.CARLINK, "a record from the adapter was refused (${e.message})")
                is EOFException -> WifiLinkEnd(By.ADAPTER, "the TCP connection ended inside a record (end of stream)")
                else -> socketFailed("the read", e)
            }

        /** One write was stuck for [ms]: the far end took nothing. */
        fun writeStalled(ms: Long): WifiLinkEnd = WifiLinkEnd(By.CARLINK, "a write to the adapter didn't drain in $ms ms")

        /** A keepalive's write threw [e]. */
        fun keepaliveFailed(e: IOException): WifiLinkEnd = socketFailed("a keepalive's write", e)

        /** The read handler (the OCBM client's dispatch) threw [e]: Carlink's own failure, not the socket's. */
        fun handlerFailed(e: IOException): WifiLinkEnd = WifiLinkEnd(By.CARLINK, "its own handling of the adapter's data failed (${named(e)})")

        /** The read loop ended with no reason of the above. */
        fun loopEnded(): WifiLinkEnd = WifiLinkEnd(By.CARLINK, "its read loop ended unexpectedly")

        private fun socketFailed(
            what: String,
            e: IOException,
        ): WifiLinkEnd =
            if (resetOrClosed(e)) {
                WifiLinkEnd(By.ADAPTER, "$what found the connection reset or closed (${named(e)})")
            } else {
                WifiLinkEnd(By.CONNECTION, "$what failed (${named(e)})")
            }

        /** A TCP reset, or a write after the far end closed: how each platform words it (the JDK, and Android's libcore). */
        private fun resetOrClosed(e: IOException): Boolean {
            val m = e.message ?: return false
            return RESET_WORDS.any { m.contains(it, ignoreCase = true) }
        }

        private val RESET_WORDS = listOf("ECONNRESET", "Connection reset", "EPIPE", "Broken pipe")

        private fun named(e: IOException): String = "${e.javaClass.simpleName}: ${e.message}"
    }
}
