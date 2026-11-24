package com.carlink.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.carlink.logging.ProbeLog
import com.carlink.ocbm.Framing
import com.carlink.ocbm.Ocbm
import com.carlink.ocbm.OcbsSetup
import com.carlink.ocbm.Reassembler
import com.carlink.ocbm.WifiBulkTransport
import com.carlink.ocbm.WifiLinkProvisioning
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** M3: the first set-up over the adapter's set-up network, against a fake adapter that speaks OCBS v1 and then OCBM. */
@RunWith(RobolectricTestRunner::class)
class SetupOverNetworkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = ServerSocket(0)
    private val bodies = LinkedBlockingQueue<ByteArray>()

    /** Every OCBM frame the fake adapter got, as "channel:first byte:size". */
    private val frames = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val store = WifiLinkStore(context, FakeSealer(), "setup_network_test")
    private var released = 0

    @After
    fun tearDown() = server.close()

    private inner class FakePort(
        val joins: Boolean = true,
        val answers: Boolean = true,
    ) : SetupOverNetwork.Port {
        override suspend fun join(): SetupOverNetwork.Joined? = if (joins) SetupOverNetwork.Joined(null, byHand = false, name = "CarLink-Setup-0000") { released++ } else null

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
    fun `the hotspot and a new link key go over the set-up exchange, and the network is let go`() {
        fakeAdapter()
        val setup = SetupOverNetwork(FakePort(), store, ProbeLog.silent())
        var asked: SetupOverNetwork.Confirm? = null
        val r =
            runBlocking {
                launch {
                    asked = setup.confirm.first { it != null }
                    assertTrue("nothing goes before the driver says send", bodies.isEmpty())
                    setup.answer(true)
                }
                setup.run("MyCar", "password")
            }
        assertEquals(SetupOverNetwork.Result.SENT, r)
        assertEquals("the driver sees the network Carlink joined", "CarLink-Setup-0000", asked!!.networkName)
        assertEquals("both points, for a code a later version may show", 2 * OcbsSetup.POINT_LEN, asked!!.transcript.size)
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
    fun `a driver who doesn't confirm, or not in time, sends nothing`() {
        fakeAdapter()
        val setup = SetupOverNetwork(FakePort(), store, ProbeLog.silent())
        val r =
            runBlocking {
                launch {
                    setup.confirm.first { it != null }
                    setup.answer(false)
                }
                setup.run("MyCar", "password")
            }
        assertEquals(SetupOverNetwork.Result.NOT_CONFIRMED, r)
        fakeAdapter()
        assertEquals(SetupOverNetwork.Result.NOT_CONFIRMED, runBlocking { SetupOverNetwork(FakePort(), store, ProbeLog.silent(), confirmMs = 200).run("MyCar", "password") })
        assertTrue(bodies.isEmpty())
        assertFalse(store.current.setUp)
    }

    @Test
    fun `a network joined by hand is taken only when its name is a set-up network's, or can't be read`() {
        assertTrue(SetupNetworkNames.acceptsByHand("CarLink-Setup-1a2b"))
        assertTrue("unreadable: the driver checks it on screen", SetupNetworkNames.acceptsByHand(null))
        assertFalse(SetupNetworkNames.acceptsByHand("Some Cafe"))
        assertEquals("CarLink-Setup-1a2b", SetupNetworkNames.ssidOf("\"CarLink-Setup-1a2b\""))
        assertEquals(null, SetupNetworkNames.ssidOf("<unknown ssid>"))
    }

    /** An adapter offering its set-up network: OCBS v1, then HELLO and MGMT_WIFI_LINK only. */
    private fun fakeAdapter(status: Int = 0) {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val session = OcbsSetup.boxHandshake(s.getInputStream(), s.getOutputStream())
                    val din = DataInputStream(s.getInputStream())
                    val reasm = Reassembler()
                    var seq = 0
                    val send = { ch: Int, pl: ByteArray -> s.getOutputStream().write(session.tx.seal(Framing.frame(ch, Ocbm.F_BOTH, seq++, pl))) }
                    while (true) {
                        val plain = session.rx.read(din) ?: break
                        reasm.push(plain, plain.size)
                        while (true) {
                            val f = reasm.next() ?: break
                            val p = f.payload
                            frames.add("${f.channel}:${p.firstOrNull()}:${p.size}")
                            // As the adapter's set-up session (24 Nov, night): CT_HELLO, and MGMT_WIFI_LINK with a body; anything
                            // else closes the link with nothing sent.
                            if (f.channel == Ocbm.CH_CTRL && p[0] == Ocbm.CT_HELLO) {
                                send(Ocbm.CH_CTRL, byteArrayOf(Ocbm.CT_HELLO_ACK, Ocbm.VERSION, 0, (Ocbm.CAP_WIFI_LINK ushr 8).toByte(), 0, 0, Ocbm.MODE_PROJECTION))
                            } else if (f.channel == Ocbm.CH_MGMT && p[0] == Ocbm.MGMT_WIFI_LINK && p.size > 1) {
                                bodies.add(p.copyOfRange(1, p.size))
                                send(Ocbm.CH_MGMT, byteArrayOf(Ocbm.MGMT_ACK, Ocbm.MGMT_WIFI_LINK, status.toByte()))
                            } else {
                                return@runCatching
                            }
                        }
                    }
                }
            }
        }
    }
}
