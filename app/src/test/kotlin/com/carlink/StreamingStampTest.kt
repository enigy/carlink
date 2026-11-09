package com.carlink

import com.carlink.CarlinkManager.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When a phone's session reaches STREAMING, its media or its key first (9 Nov bench, 67c038db). */
class StreamingStampTest {
    private val stamp = StreamingStamp()

    @Test
    fun `media before the key, then the key, is streaming`() {
        // Yoda's iPhone (iOS 15): empty now-playing frames 1.7 s before its video key.
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
}
