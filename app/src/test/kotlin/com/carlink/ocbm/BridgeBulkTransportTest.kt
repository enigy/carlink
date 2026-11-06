package com.carlink.ocbm

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.carlink.convert.BridgeStickTransport
import com.carlink.logging.ProbeLog
import com.enigy.carlink2.bridge.BridgeContract
import com.enigy.carlink2.bridge.ICarlinkBridge
import com.enigy.carlink2.bridge.IUsbBridgeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The bridge relays one session, and before API 5 its write() and close() act on whichever is open: who may use
 * them. From API 5 each open has a handle of its own: that the transports use it, and log why a session ended.
 */
@RunWith(RobolectricTestRunner::class)
class BridgeBulkTransportTest {
    @Test
    fun `only the transport opened last may write to or close the bridge's one session`() {
        assumeTrue("a never-ending stream to read", ENDLESS.exists())
        val bridge = FakeBridge()
        val adapter = open(bridge, ADAPTER)
        assertTrue(adapter.writeBulk(ByteArray(16)))
        val stick = open(bridge, STICK) // the bridge closes the adapter's session for it
        assertFalse("a superseded link's heartbeat must not reach the stick", adapter.writeBulk(ByteArray(16)))
        assertTrue(stick.writeBulk(ByteArray(31)))
        adapter.stop()
        assertEquals("the superseded link's close left the stick's session alone", 0, bridge.closes)
        assertEquals(listOf(ADAPTER, STICK), bridge.writes)
        stick.stop()
        assertEquals(1, bridge.closes)
    }

    @Test
    fun `a stick opened on request takes the session from the adapter's link, which can't touch it after`() {
        assumeTrue("a never-ending stream to read", ENDLESS.exists())
        val bridge = FakeBridge()
        val adapter = open(bridge, ADAPTER)
        assertTrue(adapter.writeBulk(ByteArray(16)))
        val stick = BridgeStickTransport.open(bridge, STICK, trace = true) ?: throw AssertionError("the stick didn't open")
        assertFalse("the adapter's heartbeat must not reach the stick", adapter.writeBulk(ByteArray(16)))
        assertTrue(stick.write(ByteArray(31), 1_000))
        assertEquals(13, stick.read(13, 1_000)?.size)
        assertTrue(stick.resetBulkOnly())
        adapter.stop()
        assertEquals("the adapter's close left the stick's session alone", 0, bridge.closes)
        assertEquals(listOf(ADAPTER, STICK), bridge.writes)
        stick.close()
        assertEquals(1, bridge.closes)
        assertEquals("closed, the stick reads nothing more", null, stick.read(13, 1_000))
    }

    @Test
    fun `on a bridge with session handles, each transport works through its own and closes only its own`() {
        assumeTrue("a never-ending stream to read", ENDLESS.exists())
        val bridge = SessionBridge()
        val adapter = open(bridge, ADAPTER)
        assertTrue(adapter.writeBulk(ByteArray(16)))
        val stick = BridgeStickTransport.open(bridge, STICK, trace = true) ?: throw AssertionError("the stick didn't open")
        assertEquals(BridgeContract.END_SUPERSEDED, bridge.sessions[0].ended)
        assertFalse("the superseded link's heartbeat reaches nothing", adapter.writeBulk(ByteArray(16)))
        assertTrue(stick.write(ByteArray(31), 1_000))
        assertEquals(13, stick.read(13, 1_000)?.size)
        assertTrue(stick.resetBulkOnly())
        adapter.stop()
        assertNull("the link's stop left the stick's session alone", bridge.sessions[1].ended)
        stick.close()
        assertEquals(BridgeContract.END_CLOSED, bridge.sessions[1].ended)
        assertNull("closed, the stick reads nothing more", stick.read(13, 1_000))
        assertEquals(listOf(ADAPTER, STICK), bridge.writes)
        assertEquals("none of the calls before API 5", 0, bridge.oldCalls)
        assertEquals("each open handed the bridge this process's Binder", listOf(BridgeSession.client, BridgeSession.client), bridge.clients)
    }

    @Test
    fun `when the bridge ends the session, the read loop's EOF says why`() {
        assumeTrue("a stream that ends at once", NOTHING.exists())
        val bridge = SessionBridge { ParcelFileDescriptor.open(NOTHING, ParcelFileDescriptor.MODE_READ_ONLY) }
        val t = BridgeBulkTransport.open(bridge, ADAPTER, ProbeLog.silent()) ?: throw AssertionError("didn't open")
        bridge.sessions.single().end(BridgeContract.END_DETACHED)
        val dead = CountDownLatch(1)
        t.setDeadHandler { dead.countDown() }
        t.start()
        assertTrue("the read loop saw EOF", dead.await(5, TimeUnit.SECONDS))
        assertEquals("bridge closed the session (EOF: ${BridgeContract.END_DETACHED})", t.lastError)
        t.stop()
    }

    @Test
    fun `a write the bridge refused because it ended the session says so`() {
        assumeTrue("a never-ending stream to read", ENDLESS.exists())
        val bridge = SessionBridge()
        val t = open(bridge, ADAPTER)
        bridge.sessions.single().end(BridgeContract.END_DETACHED)
        assertFalse(t.writeBulk(ByteArray(16)))
        assertEquals("the bridge ended the session: ${BridgeContract.END_DETACHED}", t.lastError)
        t.stop()
        assertEquals("stop closes its own session, ended or not", BridgeContract.END_DETACHED, bridge.sessions.single().ended)
    }

    @Test
    fun `a session that ended before its stream was taken is no open at all`() {
        val bridge = SessionBridge { throw AssertionError("not asked for") }
        bridge.endEachAtOnce = true
        assertNull(BridgeBulkTransport.open(bridge, ADAPTER, ProbeLog.silent()))
        assertEquals(BridgeContract.END_DETACHED, bridge.sessions.single().ended)
    }

    private fun open(
        bridge: ICarlinkBridge,
        name: String,
    ): BridgeBulkTransport {
        val t = BridgeBulkTransport.open(bridge, name, ProbeLog.silent()) ?: throw AssertionError("$name didn't open")
        t.start()
        return t
    }

    /** One session at a time, as the bridge has: each write lands in whichever session is open. */
    private class FakeBridge : ICarlinkBridge.Default() {
        val writes = mutableListOf<String>()
        var closes = 0
        private var session: String? = null

        override fun open(deviceName: String?): ParcelFileDescriptor {
            session = deviceName
            return ParcelFileDescriptor.open(ENDLESS, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun write(
            data: ByteArray?,
            timeoutMs: Int,
        ): Int {
            writes += session ?: "no session"
            return data?.size ?: -1
        }

        override fun openOnRequest(
            deviceName: String?,
            trace: Boolean,
        ): Boolean {
            session = deviceName
            return true
        }

        override fun read(
            length: Int,
            timeoutMs: Int,
        ): ByteArray? = if (session == null) null else ByteArray(length)

        override fun resetBulkOnly(): Boolean = session != null

        override fun close() {
            closes++
            session = null
        }
    }

    /** Bridge API 5: each open has a handle of its own, ended when a newer open takes over, as the bridge does. */
    private class SessionBridge(
        private val stream: () -> ParcelFileDescriptor = { ParcelFileDescriptor.open(ENDLESS, ParcelFileDescriptor.MODE_READ_ONLY) },
    ) : ICarlinkBridge.Default() {
        val writes = mutableListOf<String>()
        val sessions = mutableListOf<Session>()
        val clients = mutableListOf<IBinder?>()
        var oldCalls = 0

        // A device that leaves as it opens: the session has ended, and its stream gone, before the app asks for it.
        var endEachAtOnce = false

        override fun getApiVersion(): Int = BridgeContract.SESSION_API_VERSION

        override fun openSession(
            deviceName: String?,
            onRequest: Boolean,
            trace: Boolean,
            client: IBinder?,
        ): IUsbBridgeSession {
            sessions.lastOrNull()?.end(BridgeContract.END_SUPERSEDED)
            clients += client
            val session = Session(deviceName.orEmpty(), if (onRequest || endEachAtOnce) null else stream())
            if (endEachAtOnce) session.end(BridgeContract.END_DETACHED)
            return session.also { sessions += it }
        }

        override fun open(deviceName: String?): ParcelFileDescriptor? {
            oldCalls++
            return null
        }

        override fun write(
            data: ByteArray?,
            timeoutMs: Int,
        ): Int {
            oldCalls++
            return -1
        }

        override fun close() {
            oldCalls++
        }

        inner class Session(
            private val name: String,
            private var stream: ParcelFileDescriptor?,
        ) : IUsbBridgeSession.Default() {
            @Volatile var ended: String? = null

            fun end(reason: String) {
                if (ended == null) ended = reason
            }

            override fun takeStream(): ParcelFileDescriptor? = stream.also { stream = null }

            override fun write(
                data: ByteArray?,
                timeoutMs: Int,
            ): Int {
                if (ended != null) return -1
                writes += name
                return data?.size ?: -1
            }

            override fun read(
                length: Int,
                timeoutMs: Int,
            ): ByteArray? = if (ended == null) ByteArray(length) else null

            override fun resetBulkOnly(): Boolean = ended == null

            override fun close() = end(BridgeContract.END_CLOSED)

            override fun getEndReason(): String? = ended
        }
    }

    private companion object {
        const val ADAPTER = "/dev/bus/usb/002/001"
        const val STICK = "/dev/bus/usb/002/002"

        /** Reads that never end, like a session's socket; the transport's read loop needs one to keep running. */
        val ENDLESS = File("/dev/zero")

        /** A stream that ends at once, like a session's socket once the bridge ended it. */
        val NOTHING = File("/dev/null")
    }
}
