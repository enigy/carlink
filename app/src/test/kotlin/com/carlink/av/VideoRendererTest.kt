package com.carlink.av

import android.graphics.SurfaceTexture
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.util.Collections

/**
 * The renderer over Robolectric's MediaCodec with a pass-through fake decoder: its decoder built ahead from the last
 * stream's setup (taken, rebuilt for other parameter sets or another codec, fed nothing before the stream's own, a
 * failure there costing the stream nothing), and the picture's rules (a still screen's keyframe drawn by its own feed, a
 * kept decoder's new wait starting at a keyframe, what the manager's watchdogs read). Synthetic parameter sets, as in
 * [VideoCodecsTest]: only their header bytes matter to the renderer. Hooks between messages stand for what happens on
 * the manager's side meanwhile (the phone leaving and coming back).
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

    /** While true the decoder takes access units and gives back nothing to draw, as a poisoned one does. */
    private var silent = false

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
                if (!silent) output.put(input)
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

    // A switch of this test's own: the process's must not carry from one test to the next.
    private val switch = BuildAheadSwitch()
    private val setups = StreamSetups(switch)
    private var keyframesAsked = 0

    @Before
    fun decoders() {
        for (codec in VideoCodec.entries) ShadowMediaCodec.addDecoder(codec.mime, ShadowMediaCodec.CodecConfig(BUFFER, BUFFER, passThrough))
        // Robolectric's clock starts at 100 ms, inside the 500 ms keyframe throttle: past it, a keyframe asked is seen.
        SystemClock.sleep(10_000)
    }

    @After
    fun release() {
        ShadowMediaCodec.clearCodecs()
        surface.release()
    }

    /** One message in the seam's `[u32 BE len][payload]` framing, as VideoSeam emits it. */
    private fun framed(m: ByteArray): InputStream =
        ByteArrayInputStream(byteArrayOf((m.size ushr 24).toByte(), (m.size ushr 16).toByte(), (m.size ushr 8).toByte(), m.size.toByte()) + m)

    /** A hook run as the renderer reaches it, after the message before it is handled: a stream that is only that. */
    private fun hook(action: () -> Unit): InputStream =
        object : InputStream() {
            override fun read(): Int {
                action()
                return -1
            }
        }

    /** The seam over [parts]: messages (ByteArray) and hooks (`() -> Unit`), in order. */
    @Suppress("UNCHECKED_CAST") // a hook is a () -> Unit, whatever its static type in the vararg
    private fun seam(vararg parts: Any): InputStream =
        SequenceInputStream(Collections.enumeration(parts.map { if (it is ByteArray) framed(it) else hook(it as () -> Unit) }))

    private var renderer: VideoRenderer? = null

    /** One epoch: a renderer with the last stream's setup [ahead] of its stream or none, run over [parts] to the seam's end. */
    private fun run(
        ahead: StreamSetup?,
        vararg parts: Any,
        renderSetups: StreamSetups? = setups,
    ): VideoRenderer {
        setups.last = ahead
        val watch = PictureWatch({ gate.waiting }, { ++tick }) { wait, frame -> gate.drawn(wait, frame) }
        val r = VideoRenderer(WIDTH, HEIGHT, surface, picture = watch, setups = renderSetups) { keyframesAsked++ }
        renderer = r
        r.start()
        r.consume(seam(*parts))
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
    fun `a decoder built ahead that throws at its first use is rebuilt at once, takes the same keyframe, and building ahead goes off`() {
        failInputs = 1
        val r = run(hevcSetup, vps + sps + pps, idr, trail)
        assertEquals("the one built ahead, then one at the stream's parameter sets", listOf(VideoCodec.HEVC.mime, VideoCodec.HEVC.mime), configures)
        assertEquals("the keyframe that threw was fed again, so its frame and the next are drawn", 2L, r.framesRendered.get())
        assertEquals("no keyframe round trip", 0, keyframesAsked)
        assertTrue(gate.picture.value != null)
        assertFalse("off for the process: the next wake builds at the stream's parameter sets", switch.on)
    }

    @Test
    fun `the decoder built at the stream's parameter sets that throws too waits out the backoff, once`() {
        failInputs = 2 // the one built ahead, and the one built for the stream
        val r = run(hevcSetup, vps + sps + pps, idr, trail, idr)
        assertEquals(listOf(VideoCodec.HEVC.mime, VideoCodec.HEVC.mime), configures)
        assertEquals("nothing: the backoff holds, Robolectric's clock standing still", 0L, r.framesRendered.get())
        assertFalse(switch.on)
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
        switch.turnOff() // a decoder built ahead failed earlier in the process
        val r = run(hevcSetup, vps + sps + pps, idr)
        assertEquals(listOf(VideoCodec.HEVC.mime), configures)
        assertEquals(1L, r.framesRendered.get())
        assertTrue("built after its video came", gate.picture.value!!.decoderReadyAtMs > gate.picture.value!!.videoInAtMs)
    }

    @Test
    fun `on a still screen a keyframe's own feed draws its frame, with nothing after it`() {
        val r = run(null, vps + sps + pps, idr)
        assertEquals(1L, r.framesRendered.get())
        assertNotNull("the picture, with no second access unit to push it out", gate.picture.value)
    }

    @Test
    fun `a new wait on a kept decoder draws its picture from the next keyframe, not from frames that refer to the last phone's`() {
        var cameBack = 0L
        val r = run(hevcSetup, vps + sps + pps, idr, trail, { gate.expect().also { cameBack = tick } }, trail, trail, idr, trail)
        val picture = gate.picture.value!!
        assertTrue("drawn after the phone came back", picture.drawnAtMs > cameBack)
        assertEquals("the first phone's two, then the keyframe's and the one after it, not the two before it", 4L, r.framesRendered.get())
        assertEquals("a keyframe asked for (once: the second is within the throttle)", 1, keyframesAsked)
    }

    @Test
    fun `only a decoder built ahead that was fed and drew nothing is to blame`() {
        silent = true // it takes the access units and draws nothing
        val seen = mutableListOf<Boolean>()

        fun look() = seen.add(renderer!!.builtAheadDrewNothing)
        run(hevcSetup, { look() }, vps + sps + pps, { look() }, idr, { look() })
        assertFalse("never fed: not to blame", seen[0])
        assertFalse("proven by its stream, not yet fed: not to blame", seen[1])
        assertTrue("fed a keyframe and drew nothing: to blame", seen[2])
    }

    @Test
    fun `building ahead is the process's to turn off, once`() {
        assertSame("every manager's setups share the process's switch", StreamSetups().buildAhead, StreamSetups().buildAhead)
        assertSame(BuildAheadSwitch.process, StreamSetups().buildAhead)
        val mine = BuildAheadSwitch()
        assertTrue(mine.turnOff())
        assertFalse("already off: nothing to log", mine.turnOff())
        assertFalse(mine.on)
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
