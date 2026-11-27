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
import org.junit.Assert.assertFalse
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

                    override fun wifiLink(body: ByteArray): ByteArray? = c.mgmtAnswer(Ocbm.MGMT_WIFI_LINK, body, 5_000)
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

                    override fun wifiLink(body: ByteArray): ByteArray? = c.mgmtAnswer(Ocbm.MGMT_WIFI_LINK, body, 5_000)
                }
            }, {})
        assertEquals(WifiLinkSetup.Sent.NOT_SUPPORTED, runBlocking { setup.send("MyCar", "password") })
        assertEquals(null, wifiLinkBodies.poll(300, TimeUnit.MILLISECONDS))
        assertNotEquals(true, store.current.setUp)
    }

    @Test
    fun `the AP link's set-up runs over the Wi-Fi link, v2 with the band, and the adapter's network kept`() {
        fakeBox(caps = Ocbm.CAP_WIFI_LINK, info = AP_INFO)
        val c = connect()
        assertTrue(c.hello(5_000, untilLinkDead = true))
        val info = WifiLinkInfo.parse(c.mgmtGetInfo()!!)!!
        assertEquals(WifiLinkInfo.Mode.AP, info.mode)
        assertEquals("5", info.apBand)
        val store = WifiLinkStore(context, FakeSealer(), "wifi_link_session_test_ap")
        val setup = WifiLinkSetup(store, { sessionOf(c, overWifi = true) }, {})
        assertEquals(WifiLinkSetup.Sent.SENT_RECONNECTING, runBlocking { setup.sendAp(ApBand.GHZ_5) })
        val body = wifiLinkBodies.poll(5, TimeUnit.SECONDS)!!
        // [ver 2][key 32][band]: 34 bytes after the verb.
        assertEquals(WifiLinkProvisioning.AP_BODY_LEN, body.size)
        assertEquals(WifiLinkProvisioning.AP_BODY_VERSION, body[0])
        assertArrayEquals(store.linkKey(), body.copyOfRange(1, 33))
        assertEquals(WifiLinkProvisioning.BAND_5, body[33])
        assertTrue(store.current.apLink)
        assertEquals(ApBand.GHZ_5, store.current.band)
        assertEquals(AP_NAME, store.current.apName)
        assertEquals(AP_PASSWORD, store.apPassword())
    }

    @Test
    fun `an adapter that refuses v2 keeps nothing set up here, and one that names no network still holds the key`() {
        fakeBox(caps = Ocbm.CAP_WIFI_LINK, apReply = apAck(1, null, null))
        val c = connect()
        assertTrue(c.hello(5_000, untilLinkDead = true))
        val store = WifiLinkStore(context, FakeSealer(), "wifi_link_session_test_ap_refused")
        val setup = WifiLinkSetup(store, { sessionOf(c, overWifi = false) }, {})
        assertEquals(WifiLinkSetup.Sent.REFUSED, runBlocking { setup.sendAp(ApBand.GHZ_2_4) })
        assertFalse(store.current.setUp)
        assertEquals(WifiLinkProvisioning.BAND_2_4, wifiLinkBodies.poll(5, TimeUnit.SECONDS)!![33])
    }

    @Test
    fun `an ACK with no network sets the link up with no name, and the key is the one the adapter took`() {
        fakeBox(caps = Ocbm.CAP_WIFI_LINK, apReply = apAck(0, null, null))
        val c = connect()
        assertTrue(c.hello(5_000, untilLinkDead = true))
        val store = WifiLinkStore(context, FakeSealer(), "wifi_link_session_test_ap_unnamed")
        val setup = WifiLinkSetup(store, { sessionOf(c, overWifi = false) }, {})
        assertEquals(WifiLinkSetup.Sent.SENT, runBlocking { setup.sendAp(ApBand.GHZ_2_4) })
        val body = wifiLinkBodies.poll(5, TimeUnit.SECONDS)!!
        assertTrue(store.current.apLink)
        assertEquals("", store.current.apName)
        assertFalse(store.current.apPasswordKept)
        assertArrayEquals(body.copyOfRange(1, 33), store.linkKey())
    }

    /** [c] as the set-up sees a session: the AP link's set-up takes any session, as the hotspot link's did. */
    private fun sessionOf(
        c: OcbmClient,
        overWifi: Boolean,
    ) = object : WifiLinkSetup.Session {
        override val takesWifiLink get() = c.supportsWifiLink
        override val overWifi = overWifi

        override fun wifiLink(body: ByteArray): ByteArray? = c.mgmtAnswer(Ocbm.MGMT_WIFI_LINK, body, 5_000)
    }

    private fun connect(): OcbmClient {
        val t = WifiBulkTransport.connect(InetSocketAddress("127.0.0.1", server.localPort), key, ProbeLog.silent())
        return OcbmClient(t, ProbeLog.silent()).also {
            client = it
            it.start()
        }
    }

    /**
     * A box that speaks OCBW, then answers CT_HELLO, MGMT_GET_INFO ([info]) and MGMT_WIFI_LINK as ocbmd does: a v2 (the AP
     * link) with [apReply], anything else with status 0.
     */
    private fun fakeBox(
        caps: Int,
        refuseHello: Boolean = false,
        apReply: ByteArray = apAck(0, AP_NAME, AP_PASSWORD),
        info: String = INFO,
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
                            answer(f.channel, f.payload, caps, apReply, info)?.let { (ch, pl) -> send(ch, pl) }
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
        apReply: ByteArray,
        info: String,
    ): Pair<Int, ByteArray>? =
        when {
            channel == Ocbm.CH_CTRL && p[0] == Ocbm.CT_HELLO ->
                Ocbm.CH_CTRL to byteArrayOf(Ocbm.CT_HELLO_ACK, Ocbm.VERSION, caps.toByte(), (caps ushr 8).toByte(), 0, 0, Ocbm.MODE_PROJECTION)
            channel == Ocbm.CH_MGMT && p[0] == Ocbm.MGMT_GET_INFO -> Ocbm.CH_MGMT to byteArrayOf(Ocbm.MGMT_INFO) + info.toByteArray()
            channel == Ocbm.CH_MGMT && p[0] == Ocbm.MGMT_WIFI_LINK -> {
                wifiLinkBodies.add(p.copyOfRange(1, p.size))
                val v2 = p.size > 1 && p[1] == WifiLinkProvisioning.AP_BODY_VERSION
                Ocbm.CH_MGMT to if (v2) apReply else byteArrayOf(Ocbm.MGMT_ACK, Ocbm.MGMT_WIFI_LINK, 0)
            }
            else -> null
        }

    private companion object {
        const val INFO = """{"name":"CarLink-0000","wifi_link":{"state":"linked","channel":149,"ap_channel":36}}"""
        const val AP_INFO = """{"name":"CarLink-0000","wifi_link":{"mode":"ap","state":"linked","ap_band":"5","ap_channel":36}}"""

        /** A made-up network: the adapter's own Wi-Fi as its answer to v2 names it. */
        const val AP_NAME = "CarLink-0000"
        const val AP_PASSWORD = "example-pass"

        /** MGMT_ACK to MGMT_WIFI_LINK v2, laid out as notes/carlink.md's ADDENDUM 24 wire proposal has it. */
        fun apAck(
            status: Int,
            name: String?,
            password: String?,
        ): ByteArray {
            val head = byteArrayOf(Ocbm.MGMT_ACK, Ocbm.MGMT_WIFI_LINK, status.toByte())
            if (name == null || password == null) return head
            val n = name.toByteArray(Charsets.UTF_8)
            val pw = password.toByteArray(Charsets.US_ASCII)
            return head + byteArrayOf(n.size.toByte()) + n + byteArrayOf(pw.size.toByte()) + pw
        }
    }
}
