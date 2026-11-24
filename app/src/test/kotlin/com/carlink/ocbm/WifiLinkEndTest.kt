package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/** How a Wi-Fi link's end is told: which side ended it, why, and what the link carried (bench 24 Nov, 12:22). */
class WifiLinkEndTest {
    @Test
    fun `an end of stream is the adapter's side closing the connection, between records or inside one`() {
        assertEquals("closed by the adapter's side: the TCP connection ended between records (end of stream)", WifiLinkEnd.endOfStream().toString())
        val inside = WifiLinkEnd.readFailed(EOFException(), DEAD_MS)
        assertEquals(WifiLinkEnd.By.ADAPTER, inside.by)
        assertEquals("the TCP connection ended inside a record (end of stream)", inside.why)
    }

    @Test
    fun `Carlink's own rules say they are Carlink's`() {
        assertEquals(
            "closed by Carlink: no bytes from the adapter for 3000 ms (the dead-link rule)",
            WifiLinkEnd.readFailed(SocketTimeoutException("Read timed out"), DEAD_MS).toString(),
        )
        assertEquals("closed by Carlink: a write to the adapter didn't drain in 3000 ms", WifiLinkEnd.writeStalled(3_000).toString())
        assertEquals(
            "closed by Carlink: a record from the adapter was refused (a record failed authentication)",
            WifiLinkEnd.readFailed(OcbwLink.LinkException("a record failed authentication"), DEAD_MS).toString(),
        )
        assertEquals(
            "closed by Carlink: its own handling of the adapter's data failed (EOFException: a parser ran short)",
            WifiLinkEnd.handlerFailed(EOFException("a parser ran short")).toString(),
        )
    }

    @Test
    fun `a reset or a write after the far end closed is the adapter's side, worded as the JDK and Android word it`() {
        listOf("Connection reset", "recvfrom failed: ECONNRESET (Connection reset by peer)").forEach {
            val end = WifiLinkEnd.readFailed(SocketException(it), DEAD_MS)
            assertEquals(it, WifiLinkEnd.By.ADAPTER, end.by)
            assertEquals("the read found the connection reset or closed (SocketException: $it)", end.why)
        }
        listOf("Broken pipe", "sendto failed: EPIPE (Broken pipe)").forEach {
            assertEquals(it, WifiLinkEnd.By.ADAPTER, WifiLinkEnd.keepaliveFailed(SocketException(it)).by)
        }
    }

    @Test
    fun `any other socket failure is the connection's, in the head unit's own words`() {
        val aborted = "recvfrom failed: ECONNABORTED (Software caused connection abort)"
        assertEquals(
            "the connection failed under Carlink: the read failed (SocketException: $aborted)",
            WifiLinkEnd.readFailed(SocketException(aborted), DEAD_MS).toString(),
        )
        assertEquals(WifiLinkEnd.By.CONNECTION, WifiLinkEnd.keepaliveFailed(IOException("sendto failed: ENETUNREACH (Network is unreachable)")).by)
        assertEquals(WifiLinkEnd.By.CONNECTION, WifiLinkEnd.readFailed(IOException(), DEAD_MS).by)
    }

    @Test
    fun `the line says what the link carried and when each way last carried something`() {
        val stats =
            WifiLinkEnd.Stats(
                linkedMs = 32_210,
                recordsIn = 1_412,
                keepalivesIn = 31,
                bytesIn = 474_591,
                lastInMsAgo = 120,
                bytesOut = 58_361,
                keepalivesOut = 2,
                lastOutMsAgo = 300,
            )
        assertEquals(
            "closed by the adapter's side: the TCP connection ended between records (end of stream); linked 32210 ms; " +
                "in 1412 records (31 keepalives) 474591 B, the last 120 ms before; out 58361 B (2 keepalives), the last 300 ms before",
            WifiLinkEnd.endOfStream().line(stats),
        )
        val silent = WifiLinkEnd.endOfStream().line(stats.copy(lastInMsAgo = null, lastOutMsAgo = null))
        assertTrue(silent, silent.endsWith("; linked 32210 ms; nothing in; nothing out"))
    }

    private companion object {
        const val DEAD_MS = 3_000
    }
}
