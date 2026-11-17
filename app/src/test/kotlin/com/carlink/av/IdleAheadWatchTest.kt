package com.carlink.av

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A decoder built ahead with no phone is released after a minute, and a sign that a phone is coming holds it on. */
class IdleAheadWatchTest {
    private val watch = IdleAheadWatch()

    @Test
    fun `a decoder built ahead with no video is released a minute after it was built, and not before`() {
        val built = 5_000L // display on
        assertFalse(watch.release(now = built + 2_000, idleSince = built))
        assertFalse(watch.release(now = built + 59_999, idleSince = built))
        assertTrue(watch.release(now = built + 60_000, idleSince = built))
    }

    @Test
    fun `a phone on its way holds it a minute from the latest sign`() {
        val built = 5_000L
        watch.phoneComing(now = 50_000) // the adapter's Bluetooth link to a phone
        assertFalse("a minute from the build, but the phone is on its way", watch.release(now = 65_000, idleSince = built))
        watch.phoneComing(now = 70_000) // the next phase of its connect
        assertFalse(watch.release(now = 129_999, idleSince = built))
        assertTrue("a minute with no further sign and still no video", watch.release(now = 130_000, idleSince = built))
    }

    @Test
    fun `a sign from before the decoder was built doesn't shorten its minute`() {
        watch.phoneComing(now = 1_000)
        assertFalse(watch.release(now = 61_000, idleSince = 20_000))
        assertTrue(watch.release(now = 80_000, idleSince = 20_000))
    }

    @Test
    fun `a decoder that isn't built ahead, or has had video, is never released`() {
        assertFalse(watch.release(now = 10_000_000, idleSince = 0L))
    }
}
