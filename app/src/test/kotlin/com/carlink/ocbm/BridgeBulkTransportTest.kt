package com.carlink.ocbm

import android.os.ParcelFileDescriptor
import com.carlink.logging.ProbeLog
import com.enigy.carlink2.bridge.ICarlinkBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The bridge relays one session, and its write() and close() act on whichever is open: who may use them. */
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

    private fun open(
        bridge: FakeBridge,
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

        override fun close() {
            closes++
            session = null
        }
    }

    private companion object {
        const val ADAPTER = "/dev/bus/usb/002/001"
        const val STICK = "/dev/bus/usb/002/002"

        /** Reads that never end, like a session's socket; the transport's read loop needs one to keep running. */
        val ENDLESS = File("/dev/zero")
    }
}
