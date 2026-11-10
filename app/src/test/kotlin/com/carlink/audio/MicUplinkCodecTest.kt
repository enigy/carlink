package com.carlink.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the codec-aware mic uplink: what the `CT_UPLINK` codec byte allows and the
 * per-tick drain. The `AudioRecord`-bound half (`MicrophoneCaptureManager.drainUplink`) is not
 * JVM-testable.
 */
class MicUplinkCodecTest {
    @Test
    fun `only PCM is an encodable uplink codec`() {
        assertTrue(MicProfile.isUplinkCodecSupported(MicProfile.CODEC_PCM))
        assertFalse(MicProfile.isUplinkCodecSupported(1)) // AAC-LC
        assertFalse(MicProfile.isUplinkCodecSupported(3)) // OPUS
        // mSBC, btd's Bluetooth call audio, retired in OCBM 1.0.5: an older adapter asking for it is refused.
        assertFalse(MicProfile.isUplinkCodecSupported(4))
    }

    @Test
    fun `per-tick drain is one 20 ms chunk`() {
        assertEquals(640, MicProfile.chunkBytes(16000, 1))
        assertEquals(320, MicProfile.chunkBytes(8000, 1))
        assertEquals(1280, MicProfile.chunkBytes(16000, 2))
    }
}
