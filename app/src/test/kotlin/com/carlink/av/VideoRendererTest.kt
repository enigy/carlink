package com.carlink.av

import android.graphics.SurfaceTexture
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The renderer's decoder built ahead from the last stream's setup, over Robolectric's MediaCodec with a pass-through
 * fake decoder: taken by a stream with the same parameter sets, rebuilt for other ones or another codec, fed nothing
 * before the stream's own, and a failure there never costs the stream its backoff-free configure. Synthetic parameter
 * sets, as in [VideoCodecsTest]: only their header bytes matter to the renderer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VideoRendererTest {
    private val sc4 = byteArrayOf(0, 0, 0, 1)

    private fun nal(vararg bytes: Int): ByteArray = sc4 + bytes.map { it.toByte() }.toByteArray()

    private val vps = nal(0x40, 0x01, 0x0C, 0x01, 0xFF)
    private val sps = nal(0x42, 0x01, 0x01, 0x01, 0x60)
    private val otherSps = nal(0x42, 0x01, 0x01, 0x02, 0x61) // another phone's, or another size
    private val pps = nal(0x44, 0x01, 0xC0, 0x2C, 0xBC)
    private val idr = nal(0x26, 0x01, 0xAF, 0x00, 0x11, 0x22) // IDR_W_RADL
    private val trail = nal(0x02, 0x01, 0xE0, 0x11, 0x22) // TRAIL_R
    private val avcSps = nal(0x67, 0x42, 0x00, 0x1E, 0xAB)
    private val avcPps = nal(0x68, 0xCE, 0x38, 0x80, 0x01)
    private val avcIdr = nal(0x65, 0x88, 0x84, 0x00, 0x33)

    private val hevcSetup = StreamSetup(VideoCodec.HEVC, WIDTH, HEIGHT, vps, sps, pps)

    /** Each configure the fake decoder saw, by MIME; the next [failConfigures] of them fail. */
    private val configures = mutableListOf<String>()
    private var failConfigures = 0

    /** The next [failInputs] access units queued make the decoder throw, as a codec in error does. */
    private var failInputs = 0

    private val passThrough =
        object : ShadowMediaCodec.CodecConfig.Codec {
            override fun process(
                input: ByteBuffer,
                output: ByteBuffer,
            ) {
                if (failInputs > 0) {
                    failInputs--
                    error("made-up codec error")
                }
                output.put(input)
            }

            override fun onConfigured(
                format: MediaFormat,
                surface: Surface?,
                crypto: MediaCrypto?,
                flags: Int,
            ) {
                configures += format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (failConfigures > 0) {
                    failConfigures--
                    error("made-up configure failure")
                }
            }
        }

    private var tick = 0L
    private val gate = PictureGate()
    private val surface = Surface(SurfaceTexture(0))
    private val setups = StreamSetups()

    @Before
    fun decoders() {
        for (codec in VideoCodec.entries) ShadowMediaCodec.addDecoder(codec.mime, ShadowMediaCodec.CodecConfig(BUFFER, BUFFER, passThrough))
    }

    @After
    fun release() {
        ShadowMediaCodec.clearCodecs()
        surface.release()
    }

    /** The seam's `[u32 BE len][payload]` framing, as VideoSeam emits it. */
    private fun seam(vararg messages: ByteArray): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        for (m in messages) {
            out.write(byteArrayOf((m.size ushr 24).toByte(), (m.size ushr 16).toByte(), (m.size ushr 8).toByte(), m.size.toByte()))
            out.write(m)
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    /** One epoch: a renderer with the last stream's setup [ahead] of its stream or none, run over [messages] to the seam's end. */
    private fun run(
        ahead: StreamSetup?,
        vararg messages: ByteArray,
    ): VideoRenderer {
        setups.last = ahead
        val watch = PictureWatch({ gate.waiting }, { ++tick }) { wait, frame -> gate.drawn(wait, frame) }
        val r = VideoRenderer(WIDTH, HEIGHT, surface, picture = watch, setups = setups)
        r.start()
        r.consume(seam(*messages))
        return r
    }

    @Test
    fun `a stream with the last one's parameter sets takes the decoder built ahead, configured before its first message`() {
        val r = run(hevcSetup, vps + sps + pps, idr, trail)
        assertEquals("one configure, ahead of the stream", listOf(VideoCodec.HEVC.mime), configures)
        assertSame("kept as it was", hevcSetup, setups.last)
        assertEquals(2L, r.framesRendered.get())
        val picture = gate.picture.value
        assertNotNull(picture)
        assertTrue("ready before its video came", picture!!.decoderReadyAtMs < picture.videoInAtMs)
        assertTrue(picture.videoInAtMs < picture.drawnAtMs)
    }

    @Test
    fun `other parameter sets rebuild the decoder built ahead, and the stream's own are kept for the next`() {
        val r = run(hevcSetup, vps + otherSps + pps, idr, trail)
        assertEquals(listOf(VideoCodec.HEVC.mime, VideoCodec.HEVC.mime), configures)
        assertEquals(2L, r.framesRendered.get())
        assertTrue("built for this stream after its video came", gate.picture.value!!.decoderReadyAtMs > gate.picture.value!!.videoInAtMs)
        assertTrue("this stream's, for the next epoch", setups.last!!.sps.contentEquals(otherSps))
    }

    @Test
    fun `an H264 stream after an HEVC one gets an H264 decoder`() {
        val r = run(hevcSetup, avcSps + avcPps, avcIdr)
        assertEquals(listOf(VideoCodec.HEVC.mime, VideoCodec.H264.mime), configures)
        assertEquals(1L, r.framesRendered.get())
        assertEquals(VideoCodec.H264, setups.last?.codec)
    }

    @Test
    fun `the decoder built ahead is fed nothing until the stream's own parameter sets are complete`() {
        // VPS and SPS first, then a keyframe with no PPS anywhere yet: not this decoder's to decode, so it isn't fed.
        val r = run(hevcSetup, vps + sps, idr, pps, idr, trail)
        assertEquals(listOf(VideoCodec.HEVC.mime), configures)
        assertEquals("the second keyframe and what follows it, not the first", 2L, r.framesRendered.get())
    }

    @Test
    fun `a decoder that can't be built ahead costs nothing, and the stream's parameter sets build it at once`() {
        failConfigures = 1
        val r = run(hevcSetup, vps + sps + pps, idr)
        assertEquals(listOf(VideoCodec.HEVC.mime, VideoCodec.HEVC.mime), configures)
        assertEquals("no backoff: Robolectric's clock stands still, so one would have stopped it", 1L, r.framesRendered.get())
    }

    @Test
    fun `a decoder built ahead that fails at its first frame is built again at once, and only once`() {
        failInputs = 1
        val r = run(hevcSetup, vps + sps + pps, idr, trail, idr)
        assertEquals(listOf(VideoCodec.HEVC.mime, VideoCodec.HEVC.mime), configures)
        assertEquals(1L, r.framesRendered.get())
    }

    @Test
    fun `a decoder the stream built that fails waits out the backoff, as it always has`() {
        failInputs = 1
        val r = run(null, vps + sps + pps, idr, trail, idr)
        assertEquals("not built again within the backoff", listOf(VideoCodec.HEVC.mime), configures)
        assertEquals(0L, r.framesRendered.get())
    }

    @Test
    fun `once building ahead is off, a last stream's setup builds nothing and the stream's own build the decoder`() {
        setups.buildAhead = false // the manager's stall watch saw a decoder built ahead draw nothing
        val r = run(hevcSetup, vps + sps + pps, idr)
        assertEquals(listOf(VideoCodec.HEVC.mime), configures)
        assertEquals(1L, r.framesRendered.get())
        assertTrue("built after its video came", gate.picture.value!!.decoderReadyAtMs > gate.picture.value!!.videoInAtMs)
    }

    @Test
    fun `with no last stream, or one of another size, the stream's parameter sets build the decoder as before`() {
        val first = run(null, vps + sps + pps, idr)
        assertEquals(listOf(VideoCodec.HEVC.mime), configures)
        assertEquals(1L, first.framesRendered.get())
        assertTrue("built after its video came", gate.picture.value!!.decoderReadyAtMs > gate.picture.value!!.videoInAtMs)

        configures.clear()
        gate.expect()
        val resized = run(StreamSetup(VideoCodec.HEVC, WIDTH / 2, HEIGHT / 2, vps, sps, pps), vps + sps + pps, idr)
        assertEquals("one configure, at the stream's parameter sets", listOf(VideoCodec.HEVC.mime), configures)
        assertEquals(1L, resized.framesRendered.get())
        assertTrue(gate.picture.value!!.decoderReadyAtMs > gate.picture.value!!.videoInAtMs)
    }

    private companion object {
        const val WIDTH = 2400
        const val HEIGHT = 960
        const val BUFFER = 64 * 1024
    }
}
