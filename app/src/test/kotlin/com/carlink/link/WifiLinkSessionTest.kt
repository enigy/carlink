package com.carlink.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.carlink.logging.ProbeLog
import com.carlink.ocbm.Framing
import com.carlink.ocbm.Ocbm
import com.carlink.ocbm.OcbmClient
import com.carlink.ocbm.OcbwLink
import com.carlink.ocbm.Reassembler
import com.carlink.ocbm.WifiBulkTransport
import com.carlink.ocbm.WifiLinkInfo
import com.carlink.ocbm.WifiLinkProvisioning
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A whole OCBM session over the Wi-Fi link against a fake box on loopback: OCBW, then HELLO and its capabilities, MGMT_INFO's
 * `wifi_link`, and the set-up's MGMT_WIFI_LINK sent over that very session (it takes any session, not only USB's).
 */
@RunWith(RobolectricTestRunner::class)
class WifiLinkSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = OcbwLink.random32()
    private val server = ServerSocket(0)
    private val wifiLinkBodies = LinkedBlockingQueue<ByteArray>()
    private var client: OcbmClient? = null

    @After
    fun tearDown() {
        client?.stop(sendStop = false)
        server.close()
    }

    @Test
    fun `HELLO, MGMT_INFO and MGMT_WIFI_LINK run over the Wi-Fi link`() {
        fakeBox(caps = Ocbm.CAP_FILE or Ocbm.CAP_WIFI_LINK)
        val c = connect()
        assertTrue(c.hello(5_000, untilLinkDead = true))
        assertTrue(c.supportsWifiLink)
        val info = WifiLinkInfo.parse(c.mgmtGetInfo()!!)!!
        assertEquals(WifiLinkInfo.State.LINKED, info.state)
        assertTrue(info.channelsDiffer)

        val store = WifiLinkStore(context, FakeSealer(), "wifi_link_session_test")
        val setup =
            WifiLinkSetup(store, {
                object : WifiLinkSetup.Session {
                    override val takesWifiLink get() = c.supportsWifiLink
                    override val overWifi = true

                    override fun wifiLink(body: ByteArray): Int? = c.mgmtAction(Ocbm.MGMT_WIFI_LINK, body, 5_000)
                }
            }, {})
        assertEquals(WifiLinkSetup.Sent.SENT_RECONNECTING, runBlocking { setup.send("MyCar", "password") })
        val body = wifiLinkBodies.poll(5, TimeUnit.SECONDS)!!
        assertEquals(WifiLinkProvisioning.BODY_VERSION, body[0])
        assertArrayEquals(store.linkKey(), body.copyOfRange(1, 33))
        assertEquals("MyCar", String(body, 34, body[33].toInt()))
        assertEquals(WifiLinkProvisioning.wpaPsk("password", "MyCar"), String(body, 40, 64))
        assertTrue(store.current.setUp)

        assertEquals(WifiLinkSetup.Removed.REMOVED, runBlocking { setup.remove() })
        assertEquals(0, wifiLinkBodies.poll(5, TimeUnit.SECONDS)!!.size)
        assertEquals(AdapterLink.USB_BRIDGE, store.current.link)
    }

    @Test
    fun `a box that closes the link at HELLO ends the wait at once`() {
        fakeBox(caps = Ocbm.CAP_WIFI_LINK, refuseHello = true)
        val c = connect()
        val started = System.nanoTime()
        assertTrue(!c.hello(20_000, untilLinkDead = true))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun `an adapter without the Wi-Fi link says so, and the set-up sends nothing`() {
        fakeBox(caps = Ocbm.CAP_FILE)
        val c = connect()
        assertTrue(c.hello(5_000, untilLinkDead = true))
        val store = WifiLinkStore(context, FakeSealer(), "wifi_link_session_test_old")
        val setup =
            WifiLinkSetup(store, {
                object : WifiLinkSetup.Session {
                    override val takesWifiLink get() = c.supportsWifiLink
                    override val overWifi = false

                    override fun wifiLink(body: ByteArray): Int? = c.mgmtAction(Ocbm.MGMT_WIFI_LINK, body, 5_000)
                }
            }, {})
        assertEquals(WifiLinkSetup.Sent.NOT_SUPPORTED, runBlocking { setup.send("MyCar", "password") })
        assertEquals(null, wifiLinkBodies.poll(300, TimeUnit.MILLISECONDS))
        assertNotEquals(true, store.current.setUp)
    }

    private fun connect(): OcbmClient {
        val t = WifiBulkTransport.connect(InetSocketAddress("127.0.0.1", server.localPort), key, ProbeLog.silent())
        return OcbmClient(t, ProbeLog.silent()).also {
            client = it
            it.start()
        }
    }

    /** A box that speaks OCBW, then answers CT_HELLO, MGMT_GET_INFO and MGMT_WIFI_LINK as ocbmd does. */
    private fun fakeBox(
        caps: Int,
        refuseHello: Boolean = false,
    ) {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val session = OcbwLink.boxHandshake(s.getInputStream(), s.getOutputStream(), key)
                    val din = DataInputStream(s.getInputStream())
                    val out = s.getOutputStream()
                    val reasm = Reassembler()
                    var seq = 0

                    val send = { ch: Int, pl: ByteArray -> out.write(session.tx.seal(Framing.frame(ch, Ocbm.F_BOTH, seq++, pl))) }
                    while (true) {
                        val plain = session.rx.read(din) ?: break
                        reasm.push(plain, plain.size)
                        while (true) {
                            val f = reasm.next() ?: break
                            if (refuseHello && f.channel == Ocbm.CH_CTRL && f.payload[0] == Ocbm.CT_HELLO) return@runCatching
                            answer(f.channel, f.payload, caps)?.let { (ch, pl) -> send(ch, pl) }
                        }
                    }
                }
            }
        }
    }

    /** What ocbmd answers to one frame, as (channel, payload); null for none. */
    private fun answer(
        channel: Int,
        p: ByteArray,
        caps: Int,
    ): Pair<Int, ByteArray>? =
        when {
            channel == Ocbm.CH_CTRL && p[0] == Ocbm.CT_HELLO ->
                Ocbm.CH_CTRL to byteArrayOf(Ocbm.CT_HELLO_ACK, Ocbm.VERSION, caps.toByte(), (caps ushr 8).toByte(), 0, 0, Ocbm.MODE_PROJECTION)
            channel == Ocbm.CH_MGMT && p[0] == Ocbm.MGMT_GET_INFO -> Ocbm.CH_MGMT to byteArrayOf(Ocbm.MGMT_INFO) + INFO.toByteArray()
            channel == Ocbm.CH_MGMT && p[0] == Ocbm.MGMT_WIFI_LINK -> {
                wifiLinkBodies.add(p.copyOfRange(1, p.size))
                Ocbm.CH_MGMT to byteArrayOf(Ocbm.MGMT_ACK, Ocbm.MGMT_WIFI_LINK, 0)
            }
            else -> null
        }

    private companion object {
        const val INFO = """{"name":"CarLink-0000","wifi_link":{"state":"linked","channel":149,"ap_channel":36}}"""
    }
}
