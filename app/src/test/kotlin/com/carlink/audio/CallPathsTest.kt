package com.carlink.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CallPathsTest {
    private val sr = 16_000

    /** S16LE bytes of a tone at [amplitude] (0..1), or silence at 0. */
    private fun pcm(
        ms: Int,
        amplitude: Double,
    ): ByteArray {
        val n = sr * ms / 1000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (amplitude * 32767 * sin(2 * PI * 300 * i / sr)).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    @Test
    fun `cabin noise counts as live, digital silence does not`() {
        val quietCabin = ShortArray(sr) { ((it % 7) - 3).toShort() } // a few LSB, about -82 dBFS
        val level = CallPaths.liveLevelDbfs(quietCabin, quietCabin.size, skip = sr / 5)
        assertNotNull(level)
        assertTrue("$level", level!! >= CallPaths.SILENT_DBFS)
        val empty = ShortArray(sr)
        assertTrue(CallPaths.liveLevelDbfs(empty, empty.size, skip = sr / 5)!! < CallPaths.SILENT_DBFS)
        assertNull("too short to judge", CallPaths.liveLevelDbfs(empty, sr / 4, skip = sr / 5))
    }

    @Test
    fun `the guard trips once after three seconds of digital silence`() {
        val guard = SilenceGuard(sr)
        val silence = pcm(20, 0.0)
        var trips = 0
        repeat(200) { if (guard.feed(silence, 0, silence.size)) trips++ } // 4 s
        assertEquals(1, trips)
    }

    @Test
    fun `any sound after settling disarms the guard for the call`() {
        val guard = SilenceGuard(sr)
        val silence = pcm(20, 0.0)
        repeat(30) { assertFalse(guard.feed(silence, 0, silence.size)) } // 600 ms
        val voice = pcm(20, 0.05)
        assertFalse(guard.feed(voice, 0, voice.size))
        repeat(300) { assertFalse(guard.feed(silence, 0, silence.size)) }
    }

    @Test
    fun `silence while settling does not count`() {
        val guard = SilenceGuard(sr, settleMs = 500, holdMs = 3_000)
        val silence = pcm(20, 0.0)
        var trippedAt = -1
        for (i in 1..200) if (guard.feed(silence, 0, silence.size)) trippedAt = i * 20
        assertEquals(3_500, trippedAt)
    }
}
