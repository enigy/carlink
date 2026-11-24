package com.carlink

import com.carlink.CarlinkManager.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a phone's session reaches STREAMING, its media or its key first (9 Nov bench, 67c038db). */
class StreamingStampTest {
    private val stamp = StreamingStamp()

    @Test
    fun `media before the key, then the key, is streaming`() {
        // The older bench iPhone (iOS 15): empty now-playing frames 1.7 s before its video key.
        assertNull("not before the session is keyed", stamp.onMedia(State.CONNECTING))
        assertEquals(State.STREAMING, stamp.onKeyed(State.CONNECTING))
    }

    @Test
    fun `a key never moves a streaming session back`() {
        assertNull(stamp.onKeyed(State.STREAMING))
    }

    @Test
    fun `the key first, then media, is streaming at the media`() {
        assertEquals(State.DEVICE_CONNECTED, stamp.onKeyed(State.CONNECTING))
        assertEquals(State.STREAMING, stamp.onMedia(State.DEVICE_CONNECTED))
        assertNull(stamp.onMedia(State.STREAMING))
    }

    @Test
    fun `early media counts for its own key only`() {
        stamp.onMedia(State.CONNECTING)
        assertEquals(State.STREAMING, stamp.onKeyed(State.CONNECTING))
        assertEquals("the next phone's key without media of its own", State.DEVICE_CONNECTED, stamp.onKeyed(State.DEVICE_CONNECTED))
    }

    @Test
    fun `a phone's session ending forgets its early media`() {
        stamp.onMedia(State.CONNECTING)
        stamp.reset()
        assertEquals(State.DEVICE_CONNECTED, stamp.onKeyed(State.CONNECTING))
    }

    @Test
    fun `media with no session is not remembered`() {
        assertNull(stamp.onMedia(State.DISCONNECTED))
        assertEquals(State.DEVICE_CONNECTED, stamp.onKeyed(State.CONNECTING))
    }

    @Test
    fun `an attach to a running session is streaming at its key, though no media ever comes`() {
        // Bench 24 Nov, 12:22: the phone's first now-playing frame went out before Carlink attached, and none came after.
        assertNull("not before the session is keyed", stamp.onAttached(State.CONNECTING))
        assertEquals(State.STREAMING, stamp.onKeyed(State.CONNECTING))
    }

    @Test
    fun `an attach once the phone is keyed is streaming at once`() {
        assertEquals(State.DEVICE_CONNECTED, stamp.onKeyed(State.CONNECTING))
        assertEquals(State.STREAMING, stamp.onAttached(State.DEVICE_CONNECTED))
    }

    @Test
    fun `an attached phone that leaves leaves the next phone to its own media`() {
        stamp.onAttached(State.CONNECTING)
        assertEquals(State.STREAMING, stamp.onKeyed(State.CONNECTING))
        stamp.reset() // the attached phone's session ended; the session drops to DEVICE_CONNECTED
        assertEquals("the next phone's key, no media of its own yet", State.DEVICE_CONNECTED, stamp.onKeyed(State.DEVICE_CONNECTED))
        assertEquals(State.STREAMING, stamp.onMedia(State.DEVICE_CONNECTED))
    }

    @Test
    fun `an attach with no session is not remembered`() {
        assertNull(stamp.onAttached(State.DISCONNECTED))
        assertEquals(State.DEVICE_CONNECTED, stamp.onKeyed(State.CONNECTING))
    }

    @Test
    fun `a keyed phone is here from its key until its session ends, whatever state the session is left in`() {
        assertFalse("no key yet", stamp.phoneKeyedIn(State.DEVICE_CONNECTED))
        stamp.onKeyed(State.CONNECTING)
        assertTrue(stamp.phoneKeyedIn(State.DEVICE_CONNECTED))
        assertTrue(stamp.phoneKeyedIn(State.STREAMING))
        assertFalse("the session ended", stamp.phoneKeyedIn(State.DISCONNECTED))
        stamp.reset() // the phone left: the session stays DEVICE_CONNECTED
        assertFalse(stamp.phoneKeyedIn(State.DEVICE_CONNECTED))
        assertEquals("the next phone's media, before its key", State.STREAMING, stamp.onMedia(State.DEVICE_CONNECTED))
        assertFalse("STREAMING, and still no phone keyed", stamp.phoneKeyedIn(State.STREAMING))
        assertNull(stamp.onKeyed(State.STREAMING))
        assertTrue("its key", stamp.phoneKeyedIn(State.STREAMING))
    }
}
