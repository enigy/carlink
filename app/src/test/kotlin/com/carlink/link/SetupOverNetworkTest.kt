package com.carlink.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.carlink.logging.ProbeLog
import com.carlink.ocbm.Framing
import com.carlink.ocbm.Ocbm
import com.carlink.ocbm.OcbmFrame
import com.carlink.ocbm.OcbsSetup
import com.carlink.ocbm.OcbwLink
import com.carlink.ocbm.Reassembler
import com.carlink.ocbm.WifiBulkTransport
import com.carlink.ocbm.WifiLinkProvisioning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** M3: the first set-up over the adapter's set-up network, against a fake adapter that speaks OCBS v1 and then OCBM. */
@RunWith(RobolectricTestRunner::class)
class SetupOverNetworkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = ServerSocket(0)
    private val bodies = LinkedBlockingQueue<ByteArray>()

    /** Every OCBM frame the fake adapter got, as "channel:first byte:size". */
    private val frames = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Empty records (keepalives) the fake adapter got from Carlink. */
    private val keepalivesIn = AtomicInteger()

    /** The code the fake adapter would show as its Bluetooth name: its own view of the exchange. */
    @Volatile private var boxCode: String? = null

    /** Carlink closed the set-up link (the fake adapter read its end). */
    private val hostClosed = CountDownLatch(1)
    private val store = WifiLinkStore(context, FakeSealer(), "setup_network_test")
    private var released = 0

    @After
    fun tearDown() = server.close()

    private inner class FakePort(
        val joins: Boolean = true,
        val answers: Boolean = true,
        val name: String? = "CarLink-Setup-0000",
    ) : SetupOverNetwork.Port {
        override suspend fun join(): SetupOverNetwork.Joined? = if (joins) SetupOverNetwork.Joined(null, byHand = false, name = name) { released++ } else null

        override fun connect(joined: SetupOverNetwork.Joined): SetupOverNetwork.Linked {
            if (!answers) throw IOException("connect timed out")
            var transcript = ByteArray(0)
            val t =
                WifiBulkTransport.open(InetSocketAddress("127.0.0.1", server.localPort), ProbeLog.silent()) { i, o ->
                    OcbsSetup.hostHandshake(i, o).also { transcript = it.transcript }
                }
            return SetupOverNetwork.Linked(t, transcript)
        }
    }

    @Test
    fun `the hotspot and a new link key go only once the driver says the codes match, and the network is let go`() {
        fakeAdapter()
        val setup = SetupOverNetwork(FakePort(), store, ProbeLog.silent())
        var asked: SetupOverNetwork.Confirm? = null
        val r =
            runBlocking {
                withTimeout(TEST_MS) {
                    launch {
                        asked = setup.confirm.first { it != null }
                        // The driver opens a Bluetooth list: past the 3 s dead rule the link lives on keepalives, and nothing
                        // else crosses before the answer (the adapter shows its code at Carlink's first record).
                        while (keepalivesIn.get() < 4) delay(100)
                        assertTrue("no OCBM frame before the driver's answer: $frames", frames.isEmpty())
                        assertTrue(bodies.isEmpty())
                        setup.answer(true)
                    }
                    setup.run("MyCar", "password")
                }
            }
        assertEquals(SetupOverNetwork.Result.SENT, r)
        assertEquals("the driver sees the code the adapter shows", boxCode, asked!!.code)
        assertEquals(OcbsSetup.CODE_DIGITS, asked!!.code.length)
        assertEquals("a readable name was checked: the question asks nothing more", "CarLink-Setup-0000", asked!!.networkName)
        // Exactly what the set-up session takes: CT_HELLO (repeated until its ACK), then one MGMT_WIFI_LINK with a body.
        val hello = "${Ocbm.CH_CTRL}:${Ocbm.CT_HELLO}:"
        assertTrue(frames.toString(), frames.dropLast(1).isNotEmpty() && frames.dropLast(1).all { it.startsWith(hello) })
        // The verb and its body for "MyCar": version, key, the name and the hashed key, each with its length (1 + 104 B).
        assertEquals("${Ocbm.CH_MGMT}:${Ocbm.MGMT_WIFI_LINK}:105", frames.last())
        val body = bodies.poll(5, TimeUnit.SECONDS)!!
        assertArrayEquals(store.linkKey(), body.copyOfRange(1, 33))
        assertEquals(WifiLinkProvisioning.wpaPsk("password", "MyCar"), String(body, 40, 64))
        assertTrue(store.current.setUp)
        assertEquals(1, released)
        assertEquals(SetupOverNetwork.Phase.DONE, setup.phase.value)
        assertNull(setup.confirm.value)
    }

    @Test
    fun `no network joined, or nothing on the set-up port, says so and sets nothing up`() {
        assertEquals(SetupOverNetwork.Result.NOT_JOINED, runBlocking { SetupOverNetwork(FakePort(joins = false), store, ProbeLog.silent()).run("MyCar", "password") })
        assertEquals(SetupOverNetwork.Result.NO_ADAPTER, runBlocking { SetupOverNetwork(FakePort(answers = false), store, ProbeLog.silent()).run("MyCar", "password") })
        assertEquals("the joined network is let go after a failure too", 1, released)
        assertFalse(store.current.setUp)
    }

    @Test
    fun `an adapter that refuses the settings leaves nothing set up`() {
        fakeAdapter(status = 1)
        val setup = SetupOverNetwork(FakePort(), store, ProbeLog.silent())
        val r =
            runBlocking {
                launch {
                    setup.confirm.first { it != null }
                    setup.answer(true)
                }
                setup.run("MyCar", "password")
            }
        assertEquals(SetupOverNetwork.Result.REFUSED, r)
        assertFalse(store.current.setUp)
    }

    @Test
    fun `codes that don't match send nothing at all, and the link is closed`() {
        fakeAdapter()
        val setup = SetupOverNetwork(FakePort(), store, ProbeLog.silent())
        var again: SetupOverNetwork.Result? = null
        val r =
            runBlocking {
                withTimeout(TEST_MS) {
                    launch {
                        setup.confirm.first { it != null }
                        again = setup.run("MyCar", "password")
                        setup.answer(false)
                    }
                    setup.run("MyCar", "password")
                }
            }
        assertEquals(SetupOverNetwork.Result.NO_MATCH, r)
        assertEquals("one set-up at a time, the question up included", SetupOverNetwork.Result.BUSY, again)
        assertTrue("Carlink closed the set-up link", hostClosed.await(5, TimeUnit.SECONDS))
        assertTrue("not even CT_HELLO: $frames", frames.isEmpty())
        assertTrue(bodies.isEmpty())
        assertFalse(store.current.setUp)
        assertEquals(1, released)
        assertEquals(SetupOverNetwork.Result.NO_MATCH, setup.result.value)
    }

    @Test
    fun `no answer in time sends nothing, and the link is closed`() {
        fakeAdapter()
        val setup = SetupOverNetwork(FakePort(), store, ProbeLog.silent(), confirmMs = 300)
        assertEquals(SetupOverNetwork.Result.NOT_CONFIRMED, runBlocking { withTimeout(TEST_MS) { setup.run("MyCar", "password") } })
        assertTrue("Carlink closed the set-up link", hostClosed.await(5, TimeUnit.SECONDS))
        assertTrue(frames.toString(), frames.isEmpty())
        assertTrue(bodies.isEmpty())
        assertFalse(store.current.setUp)
        assertEquals(1, released)
        assertNull(setup.confirm.value)
    }

    @Test
    fun `the link dying while the driver is asked closes the question, and nothing is sent`() {
        val drop = CountDownLatch(1)
        fakeAdapter(drop = drop)
        val setup = SetupOverNetwork(FakePort(), store, ProbeLog.silent())
        val r =
            runBlocking {
                withTimeout(TEST_MS) {
                    launch {
                        setup.confirm.first { it != null }
                        drop.countDown() // the adapter goes (its power, out of range, its own limit) with the code up
                    }
                    setup.run("MyCar", "password")
                }
            }
        assertEquals("well inside the driver's 150 s: the question doesn't wait out a dead link", SetupOverNetwork.Result.LINK_LOST, r)
        assertNull("the question closed by itself", setup.confirm.value)
        assertTrue(frames.isEmpty())
        assertTrue(bodies.isEmpty())
        assertFalse(store.current.setUp)
        assertEquals(1, released)
    }

    @Test
    fun `a network Android won't name is asked about in the same one question`() {
        fakeAdapter()
        val setup = SetupOverNetwork(FakePort(name = null), store, ProbeLog.silent())
        var asked: SetupOverNetwork.Confirm? = null
        val r =
            runBlocking {
                withTimeout(TEST_MS) {
                    launch {
                        asked = setup.confirm.first { it != null }
                        setup.answer(false)
                    }
                    setup.run("MyCar", "password")
                }
            }
        assertEquals(SetupOverNetwork.Result.NO_MATCH, r)
        assertNull(asked!!.networkName)
        assertEquals(boxCode, asked!!.code)
    }

    @Test
    fun `a network joined by hand is taken only when its name is a set-up network's, or can't be read`() {
        assertTrue(SetupNetworkNames.acceptsByHand("CarLink-Setup-1a2b"))
        assertTrue("unreadable: the driver checks it on screen", SetupNetworkNames.acceptsByHand(null))
        assertFalse(SetupNetworkNames.acceptsByHand("Some Cafe"))
        assertEquals("CarLink-Setup-1a2b", SetupNetworkNames.ssidOf("\"CarLink-Setup-1a2b\""))
        assertEquals(null, SetupNetworkNames.ssidOf("<unknown ssid>"))
    }

    /**
     * An adapter offering its set-up network: OCBS v1, then HELLO and MGMT_WIFI_LINK only, with keepalives of its own as the
     * box sends them. [drop]: close the link once it is counted down.
     */
    private fun fakeAdapter(
        status: Int = 0,
        drop: CountDownLatch? = null,
    ) {
        thread(isDaemon = true) { runCatching { server.accept().use { s -> serve(s, status, drop) } } }
    }

    /** One set-up link as the fake adapter serves it, until either side ends it. */
    private fun serve(
        s: Socket,
        status: Int,
        drop: CountDownLatch?,
    ) {
        val session = OcbsSetup.boxHandshake(s.getInputStream(), s.getOutputStream())
        boxCode = OcbsSetup.code(session.transcript)
        val out = FakeOut(s, session.tx).also { it.keepAlive() }
        if (drop != null) thread(isDaemon = true) { if (drop.await(TEST_MS, TimeUnit.MILLISECONDS)) runCatching { s.close() } }
        // A clean end of stream, or a reset from Carlink's side (not the drop's own close): Carlink closed the link.
        val carlinkEnded =
            try {
                read(session.rx, DataInputStream(s.getInputStream()), out, status)
            } catch (_: IOException) {
                drop == null || drop.count > 0L
            }
        if (carlinkEnded) hostClosed.countDown()
    }

    /** Frames until the stream ends: true at its clean end, false when this side closes the link on a frame it doesn't take. */
    private fun read(
        rx: OcbwLink.Opener,
        din: DataInputStream,
        out: FakeOut,
        status: Int,
    ): Boolean {
        val reasm = Reassembler()
        while (true) {
            val plain = rx.read(din) ?: return true
            if (plain.isEmpty()) keepalivesIn.incrementAndGet()
            reasm.push(plain, plain.size)
            while (true) {
                val f = reasm.next() ?: break
                if (!answer(f, out, status)) return false
            }
        }
    }

    /**
     * One frame as the adapter's set-up session (24 Nov, night) takes it: CT_HELLO, and MGMT_WIFI_LINK with a body; false for
     * anything else, on which it closes the link with nothing sent.
     */
    private fun answer(
        f: OcbmFrame,
        out: FakeOut,
        status: Int,
    ): Boolean {
        val p = f.payload
        frames.add("${f.channel}:${p.firstOrNull()}:${p.size}")
        when {
            f.channel == Ocbm.CH_CTRL && p[0] == Ocbm.CT_HELLO ->
                out.send(Ocbm.CH_CTRL, byteArrayOf(Ocbm.CT_HELLO_ACK, Ocbm.VERSION, 0, (Ocbm.CAP_WIFI_LINK ushr 8).toByte(), 0, 0, Ocbm.MODE_PROJECTION))
            f.channel == Ocbm.CH_MGMT && p[0] == Ocbm.MGMT_WIFI_LINK && p.size > 1 -> {
                bodies.add(p.copyOfRange(1, p.size))
                out.send(Ocbm.CH_MGMT, byteArrayOf(Ocbm.MGMT_ACK, Ocbm.MGMT_WIFI_LINK, status.toByte()))
            }
            else -> return false
        }
        return true
    }

    /** The fake adapter's records, one at a time (the sealer's counter is the wire order), keepalives included. */
    private class FakeOut(
        private val s: Socket,
        private val tx: OcbwLink.Sealer,
    ) {
        private var seq = 0

        @Synchronized
        fun send(
            ch: Int,
            pl: ByteArray,
        ) = s.getOutputStream().write(tx.seal(Framing.frame(ch, Ocbm.F_BOTH, seq++, pl)))

        @Synchronized
        private fun keepalive() = s.getOutputStream().write(tx.keepalive())

        /** An empty record every half second until the link ends, as the box sends them when it has nothing to say. */
        fun keepAlive() {
            thread(isDaemon = true) {
                runCatching {
                    while (!s.isClosed) {
                        keepalive()
                        Thread.sleep(500)
                    }
                }
            }
        }
    }

    private companion object {
        /** Each test's own limit, so a regression fails instead of hanging the suite. */
        const val TEST_MS = 20_000L
    }
}
