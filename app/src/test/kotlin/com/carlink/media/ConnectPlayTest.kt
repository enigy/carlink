package com.carlink.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One Play for the phone at connect: the car's deferred `play()`, or the resume setting. */
class ConnectPlayTest {
    @Test
    fun `the car's play before the phone is up is delivered at the first stopped status`() {
        val c = ConnectPlay(resumeOnConnect = { false })
        // 22:06:18 prepare/play from com.android.car.media; the phone connects at 22:07:00.
        c.defer("com.android.car.media")
        assertTrue(c.hasDeferred)
        c.arm()
        assertEquals("deferred play from com.android.car.media delivered", c.onStatus(PlayStatus.STOP))
        assertFalse("delivered once", c.hasDeferred)
        assertNull("the next status decides nothing", c.onStatus(PlayStatus.STOP))
    }

    @Test
    fun `a phone already playing gets nothing and the deferred request is dropped`() {
        val c = ConnectPlay(resumeOnConnect = { true })
        c.defer("com.android.car.media")
        c.arm()
        assertNull(c.onStatus(PlayStatus.PLAY))
        assertFalse(c.hasDeferred)
    }

    @Test
    fun `the resume setting presses play for a stopped or paused phone`() {
        var on = true
        val c = ConnectPlay(resumeOnConnect = { on })
        c.arm()
        assertEquals("resume on connect", c.onStatus(PlayStatus.STOP))
        c.arm()
        assertEquals("resume on connect", c.onStatus(PlayStatus.PAUSE))
        on = false
        c.arm()
        assertNull("setting off, nothing asked", c.onStatus(PlayStatus.STOP))
    }

    @Test
    fun `nothing is sent before a session is armed, and a deferred request outlives a session that never got a status`() {
        val c = ConnectPlay(resumeOnConnect = { true })
        assertNull(c.onStatus(PlayStatus.STOP))
        c.defer("com.android.car.media")
        c.arm()
        c.disarm()
        assertTrue(c.hasDeferred)
        c.arm()
        assertEquals("deferred play from com.android.car.media delivered", c.onStatus(PlayStatus.STOP))
    }
}
