package com.carlink.link

import com.carlink.ocbm.FakeTransport
import com.carlink.ocbm.OcbwLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wi-Fi search's order: the bench address alone, then the last address, then the advert carrying this key's id. */
class WifiBoxSearchTest {
    private val key = ByteArray(32) { 0x11 }
    private val boxId = "cbb6a0d2a8710450"

    /** A fake head unit: [answers] are the addresses where a box with this key answers; [adverts] what mDNS gives. */
    private inner class FakePort(
        val wifi: Boolean = true,
        val last: WifiBoxSearch.Candidate? = null,
        val adverts: List<WifiBoxSearch.Candidate> = emptyList(),
        val answers: Set<String> = emptySet(),
        val refusing: Set<String> = emptySet(),
        val bench: WifiBoxSearch.Candidate? = null,
    ) : WifiBoxSearch.Port {
        val tried = ArrayList<String>()
        var browsedFor: String? = null
        var closed = false
        var clock = 0L

        override fun override() = bench

        override fun onWifi() = wifi

        override fun lastAddress() = last

        override fun browse(boxId: String): WifiBoxSearch.Browse {
            browsedFor = boxId
            val queue = ArrayDeque(adverts)
            return object : WifiBoxSearch.Browse {
                override fun next(waitMs: Long): WifiBoxSearch.Candidate? = queue.removeFirstOrNull() ?: null.also { clock += waitMs }

                override fun close() {
                    closed = true
                }
            }
        }

        override fun link(
            candidate: WifiBoxSearch.Candidate,
            key: ByteArray,
            connectMs: Int,
        ): WifiBoxSearch.Attempt {
            tried.add(candidate.host)
            clock += 10
            return when (candidate.host) {
                in answers -> WifiBoxSearch.Attempt.Linked(FakeTransport())
                in refusing -> WifiBoxSearch.Attempt.Failed("another key", keyRefused = true)
                else -> WifiBoxSearch.Attempt.Failed("timed out")
            }
        }

        override fun log(line: String) = Unit
    }

    private fun at(host: String) = WifiBoxSearch.Candidate(host, OcbwLink.PORT, "test")

    private fun search(
        port: FakePort,
        windowMs: Long = 5_000,
    ): WifiBoxSearch.Outcome = runBlocking { WifiBoxSearch(port).find(key, port.clock + windowMs) { port.clock } }

    @Test
    fun `the last address is tried first, and found there the search ends without mDNS`() {
        val p = FakePort(last = at("192.0.2.10"), adverts = listOf(at("192.0.2.20")), answers = setOf("192.0.2.10"))
        val o = search(p)
        assertTrue(o is WifiBoxSearch.Outcome.Found)
        assertEquals(listOf("192.0.2.10"), p.tried)
        assertTrue("the browse is always closed", p.closed)
    }

    @Test
    fun `a moved adapter is found by its advert after the last address fails`() {
        val p = FakePort(last = at("192.0.2.10"), adverts = listOf(at("192.0.2.20")), answers = setOf("192.0.2.20"))
        val o = search(p) as WifiBoxSearch.Outcome.Found
        assertEquals("192.0.2.20", o.at.host)
        assertEquals(listOf("192.0.2.10", "192.0.2.20"), p.tried)
        assertEquals("the browse looks for this key's id", boxId, p.browsedFor)
    }

    @Test
    fun `on no Wi-Fi network there is nowhere to look`() {
        val p = FakePort(wifi = false, last = at("192.0.2.10"), answers = setOf("192.0.2.10"))
        assertEquals(WifiBoxSearch.Outcome.NotFound(WifiBoxSearch.Why.NO_WIFI), search(p))
        assertTrue(p.tried.isEmpty())
    }

    @Test
    fun `nothing answering ends at the deadline, and a refused key says so`() {
        val none = FakePort(last = at("192.0.2.10"))
        assertEquals(WifiBoxSearch.Outcome.NotFound(WifiBoxSearch.Why.NO_ANSWER), search(none, windowMs = 8_000))
        assertTrue("the last address is tried again while the search lasts", none.tried.size >= 2)
        val refused = FakePort(adverts = listOf(at("192.0.2.30")), refusing = setOf("192.0.2.30"))
        assertEquals(WifiBoxSearch.Outcome.NotFound(WifiBoxSearch.Why.KEY_REFUSED), search(refused))
    }

    @Test
    fun `the bench address stands in for everything else`() {
        val p = FakePort(wifi = false, last = at("192.0.2.10"), bench = at("198.51.100.7"), answers = setOf("198.51.100.7", "192.0.2.10"))
        val o = search(p) as WifiBoxSearch.Outcome.Found
        assertEquals("198.51.100.7", o.at.host)
        assertEquals(listOf("198.51.100.7"), p.tried)
    }

    @Test
    fun `an advert is ours only when its TXT id is this key's`() {
        assertTrue(WifiBoxSearch.isOurs(mapOf("v" to "1".toByteArray(), "id" to boxId.toByteArray()), OcbwLink.boxId(key)))
        assertTrue(WifiBoxSearch.isOurs(mapOf("id" to boxId.uppercase().toByteArray()), boxId))
        assertFalse(WifiBoxSearch.isOurs(mapOf("id" to "0000000000000000".toByteArray()), boxId))
        assertFalse(WifiBoxSearch.isOurs(mapOf("v" to "1".toByteArray()), boxId))
        assertFalse(WifiBoxSearch.isOurs(mapOf("id" to null), boxId))
    }
}
