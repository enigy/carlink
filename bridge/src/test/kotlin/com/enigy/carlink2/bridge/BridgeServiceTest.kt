package com.enigy.carlink2.bridge

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder

/** Who may use the bridge, and how its sessions open, end and say why: through the service, as Carlink calls it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], shadows = [ShadowSigningPackageManager::class])
class BridgeServiceTest {
    private lateinit var controller: ServiceController<BridgeService>
    private lateinit var service: BridgeService
    private lateinit var bridge: ICarlinkBridge
    private lateinit var adapter: UsbDevice
    private var destroyed = false

    @Before
    fun setUp() {
        controller = Robolectric.buildService(BridgeService::class.java).create()
        service = controller.get()
        adapter = usbDevice(ADAPTER)
        shadowOf(service.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(adapter, true)
        val packages = shadowOf(service.packageManager)
        packages.setPackagesForUid(CARLINK_UID, BridgeContract.DISPLAY_PACKAGE)
        packages.setPackagesForUid(LOOKALIKE_UID, BridgeContract.DISPLAY_PACKAGE)
        packages.setPackagesForUid(OTHER_UID, "com.example.other")
        // Carlink signed with the first pinned key (Play app signing); the look-alike with a key of its own.
        ShadowSigningPackageManager.signers[CARLINK_UID] = ShadowSigningPackageManager.sha256(BuildConfig.CALLER_CERTS.split(',').first())
        ShadowSigningPackageManager.signers[LOOKALIKE_UID] = ByteArray(32) { 0x5A }
        ShadowBinder.setCallingUid(CARLINK_UID)
        bridge = ICarlinkBridge.Stub.asInterface(service.onBind(Intent()))
    }

    @After
    fun tearDown() {
        ShadowBinder.reset()
        ShadowSigningPackageManager.signers.clear()
        if (!destroyed) controller.destroy()
    }

    @Test
    fun `a look-alike Carlink, signed by another key and not from Play, is turned away`() {
        ShadowBinder.setCallingUid(LOOKALIKE_UID)
        assertEquals(BridgeContract.API_VERSION, bridge.apiVersion)
        assertThrows(SecurityException::class.java) { bridge.status }
        assertThrows(SecurityException::class.java) { bridge.openSession(ADAPTER, true, false, Binder()) }
        assertThrows(SecurityException::class.java) { bridge.open(ADAPTER) }
    }

    @Test
    fun `a Carlink that Play installed is let in whatever its key, as before`() {
        shadowOf(service.packageManager).setInstallSourceInfo(BridgeContract.DISPLAY_PACKAGE, PLAY_STORE, PLAY_STORE)
        ShadowBinder.setCallingUid(LOOKALIKE_UID)
        assertNotNull(openOnRequest())
    }

    @Test
    fun `an app that isn't Carlink gets the API version and nothing else`() {
        ShadowBinder.setCallingUid(OTHER_UID)
        assertEquals(BridgeContract.API_VERSION, bridge.apiVersion)
        assertThrows(SecurityException::class.java) { bridge.status }
        assertThrows(SecurityException::class.java) { bridge.hasPermission(ADAPTER) }
        assertThrows(SecurityException::class.java) { bridge.open(ADAPTER) }
        assertThrows(SecurityException::class.java) { bridge.openOnRequest(ADAPTER, false) }
        assertThrows(SecurityException::class.java) { bridge.openSession(ADAPTER, true, false, Binder()) }
        assertThrows(SecurityException::class.java) { bridge.write(byteArrayOf(1), 10) }
        assertThrows(SecurityException::class.java) { bridge.close() }
    }

    @Test
    fun `a session's handle is Carlink's alone too`() {
        val session = openOnRequest()
        ShadowBinder.setCallingUid(OTHER_UID)
        assertThrows(SecurityException::class.java) { session.write(byteArrayOf(1), 10) }
        assertThrows(SecurityException::class.java) { session.read(1, 10) }
        assertThrows(SecurityException::class.java) { session.takeStream() }
        assertThrows(SecurityException::class.java) { session.close() }
        assertThrows(SecurityException::class.java) { session.endReason }
        ShadowBinder.setCallingUid(CARLINK_UID)
        assertNull("the other app's close did nothing", session.endReason)
        assertEquals(1, session.write(byteArrayOf(1), 10))
    }

    @Test
    fun `a superseded session's handle fails, says why, and never reaches the session after it`() {
        val first = openOnRequest()
        assertEquals(1, first.write(byteArrayOf(1), 10))
        assertNull(first.endReason)
        val second = openOnRequest()
        assertEquals(BridgeContract.END_SUPERSEDED, first.endReason)
        assertEquals(-1, first.write(byteArrayOf(2), 10))
        assertNull(first.read(1, 10))
        assertEquals(-1, first.clearHalt(true))
        assertFalse(first.resetBulkOnly())
        first.close()
        assertEquals("closing the old handle didn't touch the new session", null, second.endReason)
        assertEquals(1, second.write(byteArrayOf(3), 10))
        assertEquals(1, second.read(1, 10)?.size)
        assertTrue(second.resetBulkOnly())
    }

    @Test
    fun `an open that fails before it can claim a device leaves the open session alone`() {
        val first = openOnRequest()
        assertNull(bridge.openSession(MISSING, true, false, Binder()))
        assertNull(bridge.open(MISSING))
        assertFalse(bridge.openOnRequest(MISSING, false))
        assertNull(first.endReason)
        assertEquals(1, first.write(byteArrayOf(1), 10))
        assertTrue(bridge.status, bridge.status.contains("open($ADAPTER #1 on request)"))
    }

    @Test
    fun `closing a session ends it as closed`() {
        val session = openOnRequest()
        session.close()
        assertEquals(BridgeContract.END_CLOSED, session.endReason)
        assertEquals(-1, session.write(byteArrayOf(1), 10))
        assertTrue(bridge.status, bridge.status.contains("closed(last=closed by display app)"))
        session.close()
        assertEquals("a second close changes nothing", BridgeContract.END_CLOSED, session.endReason)
    }

    @Test
    fun `a device that leaves USB ends its session as detached`() {
        val session = openOnRequest()
        service.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, adapter))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(BridgeContract.END_DETACHED, session.endReason)
        assertEquals(-1, session.write(byteArrayOf(1), 10))
    }

    @Test
    fun `the client's process dying ends its session, and an ended session stops watching the client`() {
        val client = Binder()
        val session = checkNotNull(bridge.openSession(ADAPTER, true, false, client))
        val watchers = Shadow.extract<ShadowBinder>(client).deathRecipients
        assertEquals(1, watchers.size)
        watchers.single().binderDied()
        assertEquals(BridgeContract.END_CLIENT_GONE, session.endReason)
        assertTrue("unwatched once ended", Shadow.extract<ShadowBinder>(client).deathRecipients.isEmpty())

        val next = Binder()
        checkNotNull(bridge.openSession(ADAPTER, true, false, next)).close()
        assertTrue("a closed session doesn't keep watching", Shadow.extract<ShadowBinder>(next).deathRecipients.isEmpty())
    }

    @Test
    fun `a client whose process died while it opened gets no session, and the device is let go`() {
        val first = openOnRequest()
        assertNull(bridge.openSession(ADAPTER, true, false, DeadClient()))
        assertEquals("the open went ahead, so the session before it ended", BridgeContract.END_SUPERSEDED, first.endReason)
        assertTrue(bridge.status, bridge.status.contains("closed(last=the display app's process died while it opened $ADAPTER)"))
        assertNotNull("the device is free for the next open", openOnRequest())
    }

    @Test(timeout = 10_000)
    fun `a streaming session hands its stream out once, and ending it doesn't wait out the read in flight`() {
        val session = checkNotNull(bridge.openSession(ADAPTER, false, false, Binder()))
        assertNotNull("no stream: ${bridge.status}", session.takeStream())
        assertNull("handed out once", session.takeStream())
        // The pump's IN transfer is in flight: Robolectric's connection holds it until the interface is released.
        Thread.sleep(PUMP_START_MS)
        val started = System.nanoTime()
        session.close()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("close waited $tookMs ms on the read in flight", tookMs < CLOSE_BUDGET_MS)
        assertEquals(BridgeContract.END_CLOSED, session.endReason)
        assertNull(session.takeStream())
        // The stream's EOF is the bench's to see: Robolectric's socketpair is a no-op, so here the stream has no descriptor.
    }

    @Test(timeout = 10_000)
    fun `a streaming session superseded by a stick opened on request ends at once, read in flight and all`() {
        val link = checkNotNull(bridge.openSession(ADAPTER, false, false, Binder()))
        assertNotNull(link.takeStream())
        Thread.sleep(PUMP_START_MS)
        val started = System.nanoTime()
        val stick = openOnRequest()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("the stick's open waited $tookMs ms on the link's read in flight", tookMs < CLOSE_BUDGET_MS)
        assertEquals(BridgeContract.END_SUPERSEDED, link.endReason)
        assertEquals(-1, link.write(byteArrayOf(1), 10))
        assertEquals(1, stick.write(byteArrayOf(1), 10))
    }

    @Test
    fun `the calls before API 5 act on whichever session is open, as they always have`() {
        assertTrue(bridge.openOnRequest(ADAPTER, false))
        assertEquals(1, bridge.write(byteArrayOf(1), 10))
        val session = openOnRequest()
        assertEquals("the old call reaches the session now open", 1, bridge.write(byteArrayOf(2), 10))
        bridge.close()
        assertEquals(BridgeContract.END_CLOSED, session.endReason)
        assertEquals(-1, bridge.write(byteArrayOf(3), 10))
        assertNull(bridge.read(1, 10))
    }

    @Test
    fun `unbinding ends the open session`() {
        val session = openOnRequest()
        assertFalse(service.onUnbind(Intent()))
        assertEquals(BridgeContract.END_UNBOUND, session.endReason)
    }

    @Test
    fun `the bridge stopping ends the open session`() {
        val session = openOnRequest()
        controller.destroy()
        destroyed = true
        assertEquals(BridgeContract.END_BRIDGE_STOPPED, session.endReason)
        assertEquals(-1, session.write(byteArrayOf(1), 10))
    }

    @Test
    fun `a session opened on request keeps its trace after it ended`() {
        val session = checkNotNull(bridge.openSession(ADAPTER, true, true, Binder()))
        assertEquals(1, session.write(byteArrayOf(1), 10))
        session.close()
        val trace = session.takeTrace()
        assertTrue(trace, trace.startsWith("OUT 1 of 1 B in "))
        assertEquals("taken, it is gone", "", session.takeTrace())
    }

    @Test
    fun `a transfer's timeout is bounded, and zero or less is no longer forever`() {
        assertEquals(BridgeContract.MAX_TRANSFER_TIMEOUT_MS, boundedTimeout(0))
        assertEquals(BridgeContract.MAX_TRANSFER_TIMEOUT_MS, boundedTimeout(-1))
        assertEquals(2_000, boundedTimeout(2_000))
        assertEquals(10_000, boundedTimeout(10_000))
        assertEquals(BridgeContract.MAX_TRANSFER_TIMEOUT_MS, boundedTimeout(120_000))
    }

    private fun openOnRequest(): IUsbBridgeSession = checkNotNull(bridge.openSession(ADAPTER, true, false, Binder())) { "didn't open: ${bridge.status}" }

    /** A client Binder whose process is gone: Binder refuses a death watch on it. */
    private class DeadClient : Binder() {
        override fun linkToDeath(
            recipient: android.os.IBinder.DeathRecipient,
            flags: Int,
        ): Unit = throw android.os.RemoteException("client process died")
    }

    private companion object {
        const val ADAPTER = "/dev/bus/usb/001/002"
        const val MISSING = "/dev/bus/usb/001/099"
        const val CARLINK_UID = 10_123
        const val LOOKALIKE_UID = 10_124
        const val OTHER_UID = 10_456
        const val PLAY_STORE = "com.android.vending"

        // Long enough for the pump to be in its first IN transfer; the close budget is far under a read timeout (2 s).
        const val PUMP_START_MS = 200L
        const val CLOSE_BUDGET_MS = 500L
    }
}
