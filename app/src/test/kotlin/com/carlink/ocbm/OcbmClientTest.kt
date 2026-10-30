package com.carlink.ocbm

import com.carlink.logging.ProbeLog
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32
import kotlin.concurrent.thread

/**
 * Headless tests for the OCBM client over [FakeTransport] — no adapter, no emulator.
 *
 * The two properties worth protecting here are the ones that only bite on hardware: A/V must not reach
 * a decoder before the session is keyed, and an uplink frame's bytes must match the box's parser
 * exactly, because a wrong byte there is a silent no-op rather than an error.
 */
class OcbmClientTest {
    private val log = ProbeLog.silent()
    private var client: OcbmClient? = null

    @After
    fun tearDown() {
        client?.let { runCatching { it.stop() } }
    }

    private fun newClient(t: FakeTransport): OcbmClient =
        OcbmClient(t, log).also {
            client = it
            it.start()
        }

    /** Deliver one framed OCBM frame as though the box had sent it. */
    private fun FakeTransport.deliver(
        channel: Int,
        payload: ByteArray,
    ) = feed(Framing.frame(channel, Ocbm.F_BOTH, 0, payload))

    /** `[CT_HELLO_ACK][ver][caps u32 LE][mode]` — enough for handleCtrl to latch helloAcked. */
    private fun helloAck(caps: Int = Ocbm.CAP_MFI): ByteArray =
        byteArrayOf(
            Ocbm.CT_HELLO_ACK,
            Ocbm.VERSION,
            (caps and 0xFF).toByte(),
            ((caps ushr 8) and 0xFF).toByte(),
            ((caps ushr 16) and 0xFF).toByte(),
            ((caps ushr 24) and 0xFF).toByte(),
            Ocbm.MODE_PROJECTION,
        )

    /** Pull frames off the fake wire until one is on [channel], or give up. */
    private fun FakeTransport.awaitFrameOn(
        channel: Int,
        ms: Long = 2000,
    ): ByteArray? {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            val raw = takeWritten(100) ?: continue
            val h = Framing.parseHeader(raw, 0, raw.size) ?: continue
            if (h.channel == channel) return raw.copyOfRange(Ocbm.HDR_LEN, Ocbm.HDR_LEN + h.length)
        }
        return null
    }

    private fun subscribedClient(t: FakeTransport): OcbmClient {
        val c = newClient(t)
        t.deliver(Ocbm.CH_CTRL, helloAck())
        assertTrue("helloAcked should latch from the ACK frame", c.helloAcked)
        assertTrue(c.subscribe("name: test\n".toByteArray()))
        return c
    }

    // ---- the arming guard ------------------------------------------------------------------------

    /**
     * The property that matters most: a decoder must never be fed before the session is keyed. A prior
     * session can leave kernel-buffered A/V queued ahead of the handshake, and feeding that to a
     * renderer means decrypting with a stale key or none at all.
     */
    @Test
    fun `A slash V arriving before SUBSCRIBE is dropped, not routed`() {
        val t = FakeTransport()
        val c = newClient(t)

        t.deliver(Ocbm.CH_VIDEO, ByteArray(64) { 1 })
        t.deliver(Ocbm.CH_MEDIA_AUDIO, ByteArray(32) { 2 })
        t.deliver(Ocbm.CH_METADATA, ByteArray(16) { 3 })

        assertNull("no generation may exist before SUBSCRIBE", c.lanes)
        // and nothing blew up: the frames were counted and discarded
        assertEquals(3L, c.framesIn)
    }

    @Test
    fun `subscribe is refused before CT_HELLO_ACK`() {
        val t = FakeTransport()
        val c = newClient(t)
        assertFalse(c.subscribe("x".toByteArray()))
        assertFalse(c.subscribed)
        assertNull(c.lanes)
    }

    @Test
    fun `lanes are armed on subscribe and retired on HOST_GONE`() {
        val t = FakeTransport()
        val c = subscribedClient(t)
        assertNotNull("SUBSCRIBE arms the generation", c.lanes)

        t.deliver(Ocbm.CH_CTRL, byteArrayOf(Ocbm.CT_SESSION_EVENT, Ocbm.SEV_HOST_GONE))

        assertNull("HOST_GONE retires the generation", c.lanes)
        assertFalse(c.subscribed)
    }

    @Test
    fun `the subscribe payload is the verb followed by the config verbatim`() {
        val t = FakeTransport()
        val c = newClient(t)
        t.deliver(Ocbm.CH_CTRL, helloAck())
        val cfg = "name: \"x\"\nversion: 1\n".toByteArray()
        assertTrue(c.subscribe(cfg))

        // Skip CT_HELLO if the harness wrote one; find the SUBSCRIBE.
        var payload: ByteArray? = null
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline && payload == null) {
            val p = t.awaitFrameOn(Ocbm.CH_CTRL, 200) ?: continue
            if (p.isNotEmpty() && p[0] == Ocbm.CT_SUBSCRIBE) payload = p
        }
        assertNotNull("a CT_SUBSCRIBE must reach the wire", payload)
        assertArrayEquals(byteArrayOf(Ocbm.CT_SUBSCRIBE) + cfg, payload)
    }

    // ---- uplink byte layouts ---------------------------------------------------------------------

    @Test
    fun `sendTouch encodes normalized coordinates little-endian`() {
        val t = FakeTransport()
        val c = subscribedClient(t)
        assertTrue(c.sendTouch(Ocbm.TOUCH_DOWN, 0f, 1f, finger = 2))

        val p = t.awaitFrameOn(Ocbm.CH_INPUT)!!
        // [INPUT_TOUCH][phase][nx lo][nx hi][ny lo][ny hi][finger]; ny = 1.0 -> 65535 = 0xFFFF
        assertArrayEquals(
            byteArrayOf(
                Ocbm.INPUT_TOUCH,
                Ocbm.TOUCH_DOWN,
                0,
                0,
                0xFF.toByte(),
                0xFF.toByte(),
                2,
            ),
            p,
        )
    }

    @Test
    fun `touch coordinates are clamped rather than wrapping`() {
        val t = FakeTransport()
        val c = subscribedClient(t)
        // Out-of-range input must not wrap into a valid-looking coordinate on the far edge.
        c.sendTouch(Ocbm.TOUCH_MOVE, -0.5f, 2.0f)
        val p = t.awaitFrameOn(Ocbm.CH_INPUT)!!
        assertEquals(0, ((p[3].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF))
        assertEquals(65535, ((p[5].toInt() and 0xFF) shl 8) or (p[4].toInt() and 0xFF))
    }

    @Test
    fun `night mode is an INPUT_COMMAND, not a bare command`() {
        val t = FakeTransport()
        val c = subscribedClient(t)
        assertTrue(c.sendNightMode(true))
        assertArrayEquals(
            byteArrayOf(Ocbm.INPUT_COMMAND, Ocbm.CMD_NIGHT_MODE, 1),
            t.awaitFrameOn(Ocbm.CH_INPUT)!!,
        )
    }

    @Test
    fun `media buttons carry the consumer array index`() {
        val t = FakeTransport()
        val c = subscribedClient(t)
        assertTrue(c.sendMediaButton(Ocbm.MEDIA_BTN_NEXT))
        assertArrayEquals(
            byteArrayOf(Ocbm.INPUT_MEDIA_BTN, Ocbm.MEDIA_BTN_NEXT),
            t.awaitFrameOn(Ocbm.CH_INPUT)!!,
        )
    }

    /**
     * The throttle is load-bearing, not decoration: the video seam asks for a keyframe on every gap AND
     * every decrypt failure, so a wrong key would otherwise produce one request per frame.
     */
    @Test
    fun `keyframe requests are throttled`() {
        val t = FakeTransport()
        val c = subscribedClient(t)
        assertTrue("the first request goes out", c.requestKeyframe())
        assertFalse("an immediate second is suppressed", c.requestKeyframe())
        assertFalse(c.requestKeyframe())
        // The alt lane keeps its own counter, so a main gap cannot suppress a cluster gap.
        assertTrue("the alt lane is throttled independently", c.requestAltKeyframe())
    }

    @Test
    fun `mic PCM is split at the chunk size with nothing lost`() {
        val t = FakeTransport()
        val c = subscribedClient(t)
        val pcm = ByteArray(Ocbm.MIC_CHUNK + 100) { (it % 256).toByte() }
        assertTrue(c.sendMicPcm(pcm))

        val first = t.awaitFrameOn(Ocbm.CH_MIC)!!
        val second = t.awaitFrameOn(Ocbm.CH_MIC)!!
        assertEquals(Ocbm.MIC_CHUNK, first.size)
        assertEquals(100, second.size)
        assertArrayEquals(pcm, first + second)
    }

    @Test
    fun `uplink frames are dropped and counted when there is no session`() {
        val t = FakeTransport()
        val c = newClient(t) // never subscribed
        assertFalse(c.sendTouch(Ocbm.TOUCH_DOWN, 0.5f, 0.5f))
        assertFalse(c.sendMediaButton(Ocbm.MEDIA_BTN_PLAY))
        assertNull("nothing may reach the wire", t.awaitFrameOn(Ocbm.CH_INPUT, 300))
    }

    // ---- CTRL surfacing --------------------------------------------------------------------------

    @Test
    fun `CT_UPLINK surfaces the mic gate with its negotiated format`() {
        val t = FakeTransport()
        val c = newClient(t)
        var got: Triple<Boolean, Int, Int>? = null
        c.onUplinkGate = { on, rate, ch, _ -> got = Triple(on, rate, ch) }

        // [CT_UPLINK][state=1][rate u32 LE = 16000][ch=1]
        t.deliver(Ocbm.CH_CTRL, byteArrayOf(Ocbm.CT_UPLINK, 1, 0x80.toByte(), 0x3E, 0, 0, 1))
        assertEquals(Triple(true, 16000, 1), got)
        assertTrue(c.uplinkOn)
        assertEquals(16000, c.uplinkRate)

        t.deliver(Ocbm.CH_CTRL, byteArrayOf(Ocbm.CT_UPLINK, 0, 0, 0, 0, 0, 0))
        assertEquals(Triple(false, 0, 0), got)
        assertFalse(c.uplinkOn)
    }

    @Test
    fun `CT_BT_PHASE surfaces handshake progress`() {
        val t = FakeTransport()
        val c = newClient(t)
        val seen = ArrayList<Byte>()
        c.onBtPhase = { seen.add(it) }

        for (p in listOf(Ocbm.BTP_LINK_UP, Ocbm.BTP_AUTHENTICATED, Ocbm.BTP_WIFI_HANDOFF)) {
            t.deliver(Ocbm.CH_CTRL, byteArrayOf(Ocbm.CT_BT_PHASE, p))
        }
        assertEquals(listOf(Ocbm.BTP_LINK_UP, Ocbm.BTP_AUTHENTICATED, Ocbm.BTP_WIFI_HANDOFF), seen)
        assertEquals(Ocbm.BTP_WIFI_HANDOFF, c.lastBtPhase)
    }

    /** An unknown phase must count as progress, never as an error — the box may add values. */
    @Test
    fun `an unknown BT phase is still delivered`() {
        val t = FakeTransport()
        val c = newClient(t)
        var got: Byte? = null
        c.onBtPhase = { got = it }
        t.deliver(Ocbm.CH_CTRL, byteArrayOf(Ocbm.CT_BT_PHASE, 0x42))
        assertEquals(0x42.toByte(), got)
    }

    @Test
    fun `phone presence is reported separately from host presence`() {
        val t = FakeTransport()
        val c = newClient(t)
        val phone = ArrayList<Boolean>()
        val host = ArrayList<Boolean>()
        c.onPhonePresence = { phone.add(it) }
        c.onHostPresence = { host.add(it) }

        t.deliver(Ocbm.CH_CTRL, byteArrayOf(Ocbm.CT_SESSION_EVENT, Ocbm.SEV_PHONE_PRESENT))
        t.deliver(Ocbm.CH_CTRL, byteArrayOf(Ocbm.CT_SESSION_EVENT, Ocbm.SEV_PHONE_ABSENT))
        t.deliver(Ocbm.CH_CTRL, byteArrayOf(Ocbm.CT_SESSION_EVENT, Ocbm.SEV_HOST_PRESENT))

        assertEquals(listOf(true, false), phone)
        assertEquals(listOf(true), host)
        assertFalse(c.phonePresent)
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    /**
     * A dead link and a box-side drop must be distinguishable: SEV_HOST_GONE is recoverable and the
     * heartbeat re-subscribes, while a dead transport is not and the session layer has to rebuild.
     *
     * `onLinkDead` is delivered on its own thread, not the detector's — the natural reaction is
     * `stop()`, which joins the heartbeat and tx threads, and the detector is often one of them, so an
     * inline call would join itself. Hence the latch rather than a bare read.
     */
    @Test
    fun `link death fires once and is distinct from HOST_GONE`() {
        val t = FakeTransport()
        val c = subscribedClient(t)
        val deaths =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val fired = java.util.concurrent.CountDownLatch(1)
        c.onLinkDead = {
            deaths.incrementAndGet()
            fired.countDown()
        }

        t.deliver(Ocbm.CH_CTRL, byteArrayOf(Ocbm.CT_SESSION_EVENT, Ocbm.SEV_HOST_GONE))
        assertEquals("a box-side drop is not a link death", 0, deaths.get())

        t.killLink()
        t.killLink()
        assertTrue(
            "a real death must be announced",
            fired.await(2, java.util.concurrent.TimeUnit.SECONDS),
        )
        // Give any second (incorrect) announcement time to land before asserting exactly-once.
        Thread.sleep(150)
        assertEquals("and exactly once, however many detectors fire", 1, deaths.get())
    }

    /** The callback must not run on the thread that detected the death — see the note above. */
    @Test
    fun `link death is not announced on the detecting thread`() {
        val t = FakeTransport()
        val c = subscribedClient(t)
        val callbackThread =
            java.util.concurrent.atomic
                .AtomicReference<String>()
        val fired = java.util.concurrent.CountDownLatch(1)
        c.onLinkDead = {
            callbackThread.set(Thread.currentThread().name)
            fired.countDown()
        }

        val killer = Thread.currentThread().name
        t.killLink()

        assertTrue(fired.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertNotEquals(
            "onLinkDead must not run on the detector's thread — stop() would join itself",
            killer,
            callbackThread.get(),
        )
    }

    @Test
    fun `a stopped client refuses to restart rather than failing silently`() {
        val t = FakeTransport()
        val c = subscribedClient(t)
        c.stop()
        assertTrue(c.stopped)
        assertFalse(c.isRunning)

        c.start() // must be a refusal, not a half-alive client
        assertFalse(c.isRunning)
        assertFalse(c.subscribe("x".toByteArray()))
    }

    // ---- Settings -> OCBM action mappings (2026-09-25) ------------------------------------------

    /** `[CT_PAIR_CONFIRM][1]` on CH_CTRL for Pair, `[CT_PAIR_CONFIRM][0]` for Cancel — the box reads any non-zero byte as yes. */
    @Test
    fun `pair confirm is CT_PAIR_CONFIRM with a one-byte accept flag`() {
        val t = FakeTransport()
        val c = subscribedClient(t)
        drainWritten(t)

        assertTrue(c.sendPairConfirm(accept = true))
        assertArrayEquals(byteArrayOf(Ocbm.CT_PAIR_CONFIRM, 1), t.awaitFrameOn(Ocbm.CH_CTRL))

        assertTrue(c.sendPairConfirm(accept = false))
        assertArrayEquals(byteArrayOf(Ocbm.CT_PAIR_CONFIRM, 0), t.awaitFrameOn(Ocbm.CH_CTRL))
    }

    /** The error path skips CT_STOP (dead pipe); the clean path still sends it. */
    @Test
    fun `stop sends CT_STOP only when asked to`() {
        val t1 = FakeTransport()
        val c1 = subscribedClient(t1)
        drainWritten(t1)
        c1.stop(sendStop = false)
        assertNull("dead-pipe stop must not write CT_STOP", t1.awaitFrameOn(Ocbm.CH_CTRL, ms = 300))
        assertFalse(c1.subscribed)

        val t2 = FakeTransport()
        val c2 = subscribedClient(t2)
        drainWritten(t2)
        c2.stop()
        assertArrayEquals(byteArrayOf(Ocbm.CT_STOP), t2.awaitFrameOn(Ocbm.CH_CTRL))
        client = null
    }

    // ---- CH_FILE push (the update package, remote updates plan U2) ---------------------------------

    /** `[FILE_ACK][status][crc32 u32 LE][size u32 LE]`, as ocbmd's send_file_ack writes it. */
    private fun fileAck(
        status: Byte,
        crc: Long = 0,
        size: Int = 0,
    ): ByteArray =
        ByteBuffer
            .allocate(10)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(Ocbm.FILE_ACK)
            .put(status)
            .putInt(crc.toInt())
            .putInt(size)
            .array()

    /** What a failed push said; empty for a push that succeeded or never returned. */
    private fun failure(result: Result<Unit>?): String = result?.exceptionOrNull()?.message.orEmpty()

    /** Run [push] on its own thread, as the update does (it blocks on the box's answers). */
    private fun pushing(push: () -> Result<Unit>): () -> Result<Unit>? {
        var result: Result<Unit>? = null
        val t = thread { result = push() }
        return {
            t.join(3000)
            result
        }
    }

    @Test
    fun `a push is OPEN with its mode and path, the bytes in chunks, then CLOSE with their CRC-32 and size`() {
        val t = FakeTransport()
        val c = newClient(t)
        val data = ByteArray(40_000) { (it * 7).toByte() }
        val progress = CopyOnWriteArrayList<Long>()
        val result = pushing { c.pushFile("/tmp/ocbm-update.tgz", data, timeoutMs = 2000) { done, _ -> progress.add(done) } }

        // Mode 0644, little-endian, then the path: ocbmd's FileState reads exactly this.
        val open = t.awaitFrameOn(Ocbm.CH_FILE)
        assertArrayEquals(byteArrayOf(Ocbm.FILE_OPEN, 0xA4.toByte(), 0x01, 0, 0) + "/tmp/ocbm-update.tgz".toByteArray(), open)
        t.deliver(Ocbm.CH_FILE, fileAck(Ocbm.FILE_OK))

        val got = ByteArrayOutputStream()
        var close: ByteArray? = null
        while (close == null) {
            val f = t.awaitFrameOn(Ocbm.CH_FILE) ?: break
            if (f[0] == Ocbm.FILE_DATA) {
                assertTrue("a chunk fits one frame with room to spare", f.size <= 16 * 1024 + 1)
                got.write(f, 1, f.size - 1)
            } else {
                close = f
            }
        }
        assertArrayEquals(data, got.toByteArray())
        val crc = CRC32().apply { update(data) }.value
        val want =
            ByteBuffer
                .allocate(9)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(Ocbm.FILE_CLOSE)
                .putInt(crc.toInt())
                .putInt(data.size)
                .array()

        assertArrayEquals(want, close)
        t.deliver(Ocbm.CH_FILE, fileAck(Ocbm.FILE_OK, crc, data.size))

        assertTrue(result()?.isSuccess == true)
        assertEquals(listOf(16_384L, 32_768L, 40_000L), progress.toList())
    }

    @Test
    fun `a push the box won't open, or can't verify, fails and says what the box said`() {
        val t = FakeTransport()
        val c = newClient(t)
        val refused = pushing { c.pushFile("/tmp/x", ByteArray(10), timeoutMs = 2000) }
        assertNotNull(t.awaitFrameOn(Ocbm.CH_FILE))
        t.deliver(Ocbm.CH_FILE, fileAck(Ocbm.FILE_ERR_OPEN))
        assertTrue(failure(refused()).contains("OPEN_FAILED"))
        assertNull("nothing follows a refused OPEN", t.awaitFrameOn(Ocbm.CH_FILE, ms = 300))

        val mismatch = pushing { c.pushFile("/tmp/x", ByteArray(10), timeoutMs = 2000) }
        assertNotNull(t.awaitFrameOn(Ocbm.CH_FILE))
        t.deliver(Ocbm.CH_FILE, fileAck(Ocbm.FILE_OK))
        assertEquals(Ocbm.FILE_DATA, t.awaitFrameOn(Ocbm.CH_FILE)?.get(0))
        assertEquals(Ocbm.FILE_CLOSE, t.awaitFrameOn(Ocbm.CH_FILE)?.get(0))
        t.deliver(Ocbm.CH_FILE, fileAck(Ocbm.FILE_ERR_VERIFY, 1, 9))
        assertTrue(failure(mismatch()).contains("VERIFY_FAILED"))
    }

    @Test
    fun `a write the box fails ends the push with the box's word`() {
        val t = FakeTransport()
        val c = newClient(t)
        val failed = pushing { c.pushFile("/tmp/x", ByteArray(100_000), timeoutMs = 2000) }
        assertNotNull(t.awaitFrameOn(Ocbm.CH_FILE))
        t.deliver(Ocbm.CH_FILE, fileAck(Ocbm.FILE_OK))
        assertEquals(Ocbm.FILE_DATA, t.awaitFrameOn(Ocbm.CH_FILE)?.get(0))
        // ocbmd answers a chunk it could not write with FILE_ERR_WRITE and drops the transfer; the push stops at the
        // next chunk, or takes it as CLOSE's answer if every chunk was already out.
        t.deliver(Ocbm.CH_FILE, fileAck(Ocbm.FILE_ERR_WRITE))
        assertTrue(failure(failed()).contains("IO_FAILED"))
    }

    @Test
    fun `a push with no answer gives up`() {
        val t = FakeTransport()
        val c = newClient(t)
        val silent = pushing { c.pushFile("/tmp/x", ByteArray(10), timeoutMs = 200) }
        assertTrue(failure(silent()).contains("FILE_OPEN: no answer"))
    }

    private fun drainWritten(t: FakeTransport) {
        while (t.takeWritten(50) != null) Unit
    }
}
