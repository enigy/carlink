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
import org.junit.Assert.assertNull
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
 * kept decoder's new wait starting at a keyframe, a frame from before a wait never its picture, what the manager's
 * watchdogs read). Synthetic parameter sets, as in [VideoCodecsTest]: only their header bytes matter to the renderer.
 * Hooks between messages stand for what happens on the manager's side meanwhile (the phone leaving and coming back);
 * [ScriptedShadowMediaCodec] gives the decoder a real one's late output, its errors, and hooks inside a step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ScriptedShadowMediaCodec::class])
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

    /** Each configure the fake decoder saw, by MIME; the next [failConfigures] of them fail, and those numbered in [failConfigureAt]. */
    private val configures = mutableListOf<String>()
    private var failConfigures = 0
    private var failConfigureAt = emptySet<Int>()

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
                if (configures.size in failConfigureAt) error("made-up configure failure")
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
        ScriptedShadowMediaCodec.reset()
        for (codec in VideoCodec.entries) ShadowMediaCodec.addDecoder(codec.mime, ShadowMediaCodec.CodecConfig(BUFFER, BUFFER, passThrough))
        // Robolectric's clock starts at 100 ms, inside the 500 ms keyframe throttle: past it, a keyframe asked is seen.
        SystemClock.sleep(10_000)
    }

    @After
    fun release() {
        ScriptedShadowMediaCodec.reset()
        ShadowMediaCodec.clearCodecs()
        surface.release()
    }

    /** One message in the seam's `[u32 BE len][payload]` framing, as VideoSeam emits it. */
    private fun framed(m: ByteArray): InputStream = ByteArrayInputStream(framedBytes(m))

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
    ): VideoRenderer = runOver(ahead, seam(*parts), renderSetups)

    /** One epoch over the seam [ins] itself (its `available()` is what the renderer reads as a next message waiting). */
    private fun runOver(
        ahead: StreamSetup?,
        ins: InputStream,
        renderSetups: StreamSetups? = setups,
    ): VideoRenderer {
        setups.last = ahead
        val watch = PictureWatch({ gate.waiting }, { ++tick }, gate::videoIn) { wait, frame -> gate.drawn(wait, frame) }
        val r = VideoRenderer(WIDTH, HEIGHT, surface, picture = watch, setups = renderSetups) { keyframesAsked++ }
        renderer = r
        r.start()
        r.consume(ins)
        return r
    }

    /** All of [messages] in one stream, so each next one is already waiting (`available()`) while the one before is handled. */
    private fun queuedUp(vararg messages: ByteArray): InputStream = ByteArrayInputStream(messages.fold(ByteArray(0)) { all, m -> all + framedBytes(m) })

    private fun framedBytes(m: ByteArray): ByteArray = byteArrayOf((m.size ushr 24).toByte(), (m.size ushr 16).toByte(), (m.size ushr 8).toByte(), m.size.toByte()) + m

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
        ScriptedShadowMediaCodec.lateMs = 30 // decoded after its feed returns, as a hardware decoder's frame is
        val r = run(null, vps + sps + pps, idr)
        assertEquals(1L, r.framesRendered.get())
        assertNotNull("the picture, with no second access unit to push it out", gate.picture.value)
        assertTrue("looked for in slices", ScriptedShadowMediaCodec.longestLookUs <= 5_000)
    }

    @Test
    fun `with the next message already waiting, a keyframe's feed doesn't look for its frame, as that message pushes it out`() {
        ScriptedShadowMediaCodec.lateMs = 30
        runOver(null, queuedUp(vps + sps + pps, idr, trail, trail))
        assertEquals("no look held the next access unit's feed", 0, ScriptedShadowMediaCodec.timedLooks)
    }

    @Test
    fun `a keyframe's look ends within a slice of the next message coming`() {
        ScriptedShadowMediaCodec.lateMs = 200 // longer than the look: it can only end by the next message
        val first = framedBytes(vps + sps + pps) + framedBytes(idr)
        val next = framedBytes(trail)
        val arriving = Arriving(first, next)
        ScriptedShadowMediaCodec.onTimedLook = { arriving.arrived = true } // the next access unit lands during the first slice
        runOver(null, arriving)
        assertEquals("one slice, then the next message's feed", 1, ScriptedShadowMediaCodec.timedLooks)
        assertTrue(ScriptedShadowMediaCodec.longestLookUs <= 5_000)
    }

    @Test
    fun `a frame still in the decoder when the phone leaves is never the next phone's picture`() {
        ScriptedShadowMediaCodec.lateMs = 30
        var newKeyframeQueuedAt = 0L
        ScriptedShadowMediaCodec.onQueued = { n -> if (n == 3) newKeyframeQueuedAt = tick }
        val r =
            run(
                null,
                vps + sps + pps,
                idr, // the first phone's picture
                trail, // its last access unit: queued, its frame not out yet
                {
                    Thread.sleep(60) // its frame is out now, and drained only after the wait begins
                    gate.expect() // the phone left; the next one comes on the same parameter sets
                },
                vps + sps + pps,
                idr,
            )
        assertEquals("the first phone's keyframe and the next one's; the frame in between dropped unseen", 2L, r.framesRendered.get())
        assertTrue("the next phone's picture is its own keyframe's", gate.picture.value!!.drawnAtMs > newKeyframeQueuedAt)
    }

    @Test
    fun `an access unit queued as a new wait begins counts under the wait before it`() {
        ScriptedShadowMediaCodec.onQueued = { n -> if (n == 1) gate.expect() } // the phone leaves right as its keyframe is queued
        val r = run(null, vps + sps + pps, idr)
        assertEquals("fed before the new wait: dropped unseen while it waits", 0L, r.framesRendered.get())
        assertNull("never its picture", gate.picture.value)
    }

    @Test
    fun `a frame judged before a new wait begins is offered to the wait before it, and refused`() {
        ScriptedShadowMediaCodec.onRendered = { gate.expect() } // the phone leaves between the frame's judging and its offer
        val r = run(null, vps + sps + pps, idr)
        assertEquals(1L, r.framesRendered.get())
        assertNull(gate.picture.value)
    }

    @Test
    fun `a codec error at a frame's release between feeds is recovered, not the end of the consumer`() {
        ScriptedShadowMediaCodec.lateMs = 100 // past the keyframe's 60 ms look: its frame comes out at a later message's drain
        ScriptedShadowMediaCodec.failReleases = 1
        val r =
            run(
                hevcSetup,
                vps + sps + pps,
                idr,
                { Thread.sleep(120) }, // the frame is out
                vps + sps + pps, // a message that feeds nothing: its drain finds the frame, and the release throws
                { ScriptedShadowMediaCodec.lateMs = 0 },
                idr,
            )
        assertFalse("the decoder built ahead failed at its first use: off for the process", switch.on)
        assertEquals("rebuilt at the stream's parameter sets at once", listOf(VideoCodec.HEVC.mime, VideoCodec.HEVC.mime), configures)
        assertEquals("the next keyframe is drawn", 1L, r.framesRendered.get())
        assertNotNull(gate.picture.value)
    }

    @Test
    fun `when the decoder built for the stream after a failure can't be configured either, the backoff holds`() {
        failInputs = 1 // the one built ahead throws at its first keyframe
        failConfigureAt = setOf(2) // and the one built at once for the stream can't be configured
        val r = run(hevcSetup, vps + sps + pps, idr, idr, trail, idr)
        assertEquals("no third configure inside the backoff (Robolectric's clock stands still)", 2, configures.size)
        assertEquals(0L, r.framesRendered.get())
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
    fun `a decoder built ahead is idle until video comes, and only one fed that drew nothing is to blame`() {
        silent = true // it takes the access units and draws nothing
        val seen = mutableListOf<Pair<Long, Boolean>>()

        fun look() = seen.add(renderer!!.idleAheadSince to renderer!!.builtAheadDrewNothing)
        run(hevcSetup, { look() }, vps + sps + pps, { look() }, idr, { look() })
        assertTrue("built ahead, no video yet: idle since it was built", seen[0].first != 0L)
        assertFalse("never fed: not to blame", seen[0].second)
        assertEquals("its video came: not idle", 0L, seen[1].first)
        assertFalse("proven by its stream, not yet fed: not to blame", seen[1].second)
        assertTrue("fed a keyframe and drew nothing: to blame", seen[2].second)
    }

    @Test
    fun `an epoch rebuilt by the picture watchdog builds nothing ahead, and still leaves its stream's setup for the next`() {
        val r = run(hevcSetup, vps + otherSps + pps, idr, renderSetups = setups.notAhead())
        assertEquals("one configure, at the stream's parameter sets", listOf(VideoCodec.HEVC.mime), configures)
        assertEquals(1L, r.framesRendered.get())
        assertTrue(gate.picture.value!!.decoderReadyAtMs > gate.picture.value!!.videoInAtMs)
        assertTrue("its stream's, for the next wake", setups.last!!.sps.contentEquals(otherSps))
        assertTrue("the same switch", setups.notAhead().buildAhead === setups.buildAhead)
        assertNull("builds nothing ahead", setups.notAhead().aheadFrom)
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

    /** A seam whose [later] bytes are there to read from the start, but only show as waiting (`available()`) once [arrived]. */
    private class Arriving(
        first: ByteArray,
        private val later: ByteArray,
    ) : InputStream() {
        @Volatile var arrived = false
        private val bytes = ByteArrayInputStream(first + later)

        override fun read(): Int = bytes.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = bytes.read(b, off, len)

        override fun available(): Int = if (arrived) bytes.available() else maxOf(0, bytes.available() - later.size)
    }

    private companion object {
        const val WIDTH = 2400
        const val HEIGHT = 960
        const val BUFFER = 64 * 1024
    }
}
