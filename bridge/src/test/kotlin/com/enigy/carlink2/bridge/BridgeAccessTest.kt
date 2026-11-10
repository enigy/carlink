package com.enigy.carlink2.bridge

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Binder
import android.os.Looper
import android.os.RemoteException
import org.json.JSONObject
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
import java.time.Duration

/** API 6: other apps' access, the owner's opt-in. Off, nothing changes; on, an approved app gets the adapter only when Carlink hands it over. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], shadows = [ShadowSigningPackageManager::class])
class BridgeAccessTest {
    private lateinit var controller: ServiceController<BridgeService>
    private lateinit var bridge: ICarlinkBridge
    private val defaultSigner = PackageFacts.signer

    @Before
    fun setUp() {
        PackageFacts.signer = { _, packageName -> ShadowSigningPackageManager.packageSigners[packageName] }
        start()
        val usb = shadowOf(controller.get().getSystemService(UsbManager::class.java))
        usb.addOrUpdateUsbDevice(usbDevice(ADAPTER), true)
        usb.addOrUpdateUsbDevice(usbDevice(STICK, vendorId = 0x0781, productId = 0x5567), true)
        val carlinkKey = ShadowSigningPackageManager.sha256(BuildConfig.CALLER_CERTS.split(',').first())
        install(BridgeContract.DISPLAY_PACKAGE, "Carlink", CARLINK_UID, carlinkKey)
        install(PROBE, "Probe", PROBE_UID, PROBE_KEY)
        install(SECOND, "Second", SECOND_UID, SECOND_KEY)
    }

    @After
    fun tearDown() {
        PackageFacts.signer = defaultSigner
        ShadowBinder.reset()
        ShadowSigningPackageManager.signers.clear()
        ShadowSigningPackageManager.packageSigners.clear()
        controller.destroy()
    }

    @Test
    fun `off, the default, another app is refused as before and nothing is recorded`() {
        val refusal = calling(PROBE_UID) { assertThrows(SecurityException::class.java) { bridge.openSession(ADAPTER, false, false, Binder()) } }
        assertTrue(refusal.message, refusal.message!!.contains("is not ${BridgeContract.DISPLAY_PACKAGE}"))
        val access = access()
        assertFalse(access.getBoolean("on"))
        assertEquals(0, access.getJSONArray("requests").length())
        assertTrue(bridge.status, bridge.status.contains("access=off"))
    }

    @Test
    fun `on, an app that isn't approved is refused with the owner's way in, recorded, refreshed, and a ninth drops the oldest`() {
        bridge.setOtherAppsAllowed(true)
        val refusal = calling(PROBE_UID) { assertThrows(SecurityException::class.java) { bridge.openSession(ADAPTER, false, false, Binder()) } }
        assertEquals(
            "$PROBE isn't approved for the Carlink USB bridge. The owner approves it in Carlink › Settings › Bridge Configuration.",
            refusal.message,
        )
        var request = access().getJSONArray("requests").getJSONObject(0)
        assertEquals(PROBE, request.getString("package"))
        assertEquals("Probe", request.getString("label"))
        assertEquals(fingerprint(PROBE_KEY), request.getString("sha256"))
        assertEquals(1, request.getInt("count"))
        assertFalse(request.getBoolean("keyChanged"))

        calling(PROBE_UID) { assertThrows(SecurityException::class.java) { bridge.status } }
        request = access().getJSONArray("requests").getJSONObject(0)
        assertEquals("refreshed, not added", 1, access().getJSONArray("requests").length())
        assertEquals(2, request.getInt("count"))

        repeat(BridgeContract.MAX_ACCESS_REQUESTS) { i ->
            install("com.example.app$i", "App $i", 10_600 + i, ByteArray(32) { (0x30 + i).toByte() })
            calling(10_600 + i) { assertThrows(SecurityException::class.java) { bridge.status } }
        }
        val requests = access().getJSONArray("requests")
        assertEquals(BridgeContract.MAX_ACCESS_REQUESTS, requests.length())
        assertTrue("the oldest, the probe, was dropped", (0 until requests.length()).none { requests.getJSONObject(it).getString("package") == PROBE })
        assertEquals("newest first", "com.example.app${BridgeContract.MAX_ACCESS_REQUESTS - 1}", requests.getJSONObject(0).getString("package"))
    }

    @Test
    fun `a refused app retrying in a loop is counted in memory and written at most every few seconds`() {
        bridge.setOtherAppsAllowed(true)
        repeat(5) { calling(PROBE_UID) { assertThrows(SecurityException::class.java) { bridge.status } } }
        assertEquals("counted at once", 5, access().getJSONArray("requests").getJSONObject(0).getInt("count"))
        assertEquals("only the first call was written", 1, storedCount())
        idle(Duration.ofSeconds(6))
        assertEquals("the rest followed within the window", 5, storedCount())
        calling(PROBE_UID) { assertThrows(SecurityException::class.java) { bridge.status } }
        assertEquals("a call after the window writes at once", 6, storedCount())
    }

    private fun storedCount(): Int {
        val prefs = controller.get().getSharedPreferences("bridge_access", android.content.Context.MODE_PRIVATE)
        return org.json.JSONArray(prefs.getString("requests", "[]")).getJSONObject(0).getInt("count")
    }

    @Test
    fun `an approved app opens nothing until it is handed the adapter, and then only the adapter`() {
        approve(PROBE, PROBE_UID, PROBE_KEY)
        assertNull(calling(PROBE_UID) { bridge.openSession(ADAPTER, false, false, Binder()) })
        bridge.handOver(PROBE)
        assertNull("a stick is no adapter", calling(PROBE_UID) { bridge.openSession(STICK, true, false, Binder()) })
        val session = calling(PROBE_UID) { bridge.openSession(ADAPTER, true, false, Binder()) }
        assertNotNull(session)
        assertEquals(1, calling(PROBE_UID) { session!!.write(byteArrayOf(1), 10) })
        val access = access()
        assertEquals(PROBE, access.getString("holder"))
        assertEquals(PROBE, access.getJSONObject("handedTo").getString("package"))
        val approval = access.getJSONArray("approved").getJSONObject(0)
        assertEquals(1, approval.getInt("sessions"))
        assertThrows("its handle is its own", SecurityException::class.java) { session!!.write(byteArrayOf(1), 10) }
        assertEquals("Carlink's old write doesn't reach the app's session", -1, bridge.write(byteArrayOf(1), 10))
    }

    @Test
    fun `an approved app may not make Carlink's calls, access or older`() {
        approve(PROBE, PROBE_UID, PROBE_KEY)
        bridge.handOver(PROBE)
        calling(PROBE_UID) {
            val refused: List<() -> Unit> =
                listOf(
                    { bridge.access },
                    { bridge.setOtherAppsAllowed(false) },
                    { bridge.approve(PROBE, fingerprint(PROBE_KEY)) },
                    { bridge.revoke(SECOND) },
                    { bridge.dismissRequest(SECOND) },
                    { bridge.handOver(PROBE) },
                    { bridge.takeBack() },
                    { bridge.setAccessListener(null) },
                    { bridge.open(ADAPTER) },
                    { bridge.openOnRequest(ADAPTER, false) },
                    { bridge.write(byteArrayOf(1), 10) },
                    { bridge.read(1, 10) },
                    { bridge.clearHalt(true) },
                    { bridge.resetBulkOnly() },
                    { bridge.takeTrace() },
                    { bridge.close() },
                    // API 7: the update is Carlink's alone too.
                    { bridge.installUpdate("{}", null) },
                    { bridge.canInstallPackages() },
                )
            refused.forEach { call -> assertThrows(SecurityException::class.java) { call() } }
            assertTrue(bridge.hasPermission(ADAPTER))
            assertFalse(bridge.hasPermission(STICK))
            assertEquals(BridgeContract.PERMISSION_NO_DEVICE, bridge.requestPermission(STICK))
        }
        assertTrue("still handed over", access().has("handedTo") && !access().isNull("handedTo"))
    }

    @Test
    fun `a handover is one app's, and Carlink's open or take-back ends it`() {
        approve(PROBE, PROBE_UID, PROBE_KEY)
        approve(SECOND, SECOND_UID, SECOND_KEY)
        bridge.handOver(PROBE)
        assertNull("another approved app gets nothing", calling(SECOND_UID) { bridge.openSession(ADAPTER, true, false, Binder()) })
        val first = checkNotNull(calling(PROBE_UID) { bridge.openSession(ADAPTER, true, false, Binder()) })
        val carlink = checkNotNull(bridge.openSession(ADAPTER, true, false, Binder()))
        assertEquals(BridgeContract.END_TAKEN_BACK, calling(PROBE_UID) { first.endReason })
        assertTrue(access().isNull("handedTo"))

        val refusal = assertThrows(IllegalStateException::class.java) { bridge.handOver(PROBE) }
        assertTrue(refusal.message, refusal.message!!.contains("still open"))
        carlink.close()
        bridge.handOver(PROBE)
        val second = checkNotNull(calling(PROBE_UID) { bridge.openSession(ADAPTER, true, false, Binder()) })
        bridge.takeBack()
        assertEquals(BridgeContract.END_TAKEN_BACK, calling(PROBE_UID) { second.endReason })
        assertNull(calling(PROBE_UID) { bridge.openSession(ADAPTER, true, false, Binder()) })
    }

    @Test
    fun `a handover ends when the app's process dies, or after a minute without its session`() {
        approve(PROBE, PROBE_UID, PROBE_KEY)
        bridge.handOver(PROBE)
        val client = Binder()
        val session = checkNotNull(calling(PROBE_UID) { bridge.openSession(ADAPTER, true, false, client) })
        Shadow.extract<ShadowBinder>(client).deathRecipients.single().binderDied()
        assertEquals(BridgeContract.END_CLIENT_GONE, calling(PROBE_UID) { session.endReason })
        assertTrue(access().isNull("handedTo"))

        bridge.handOver(PROBE)
        idle(Duration.ofSeconds(59))
        assertFalse("not yet", access().isNull("handedTo"))
        calling(PROBE_UID) { checkNotNull(bridge.openSession(ADAPTER, true, false, Binder())).close() }
        idle(Duration.ofSeconds(59))
        assertFalse("its session started the minute again", access().isNull("handedTo"))
        idle(Duration.ofSeconds(2))
        assertTrue(access().isNull("handedTo"))
    }

    @Test
    fun `removing an app, or turning access off, ends its session as revoked, and off forgets everything`() {
        approve(PROBE, PROBE_UID, PROBE_KEY)
        bridge.handOver(PROBE)
        val first = checkNotNull(calling(PROBE_UID) { bridge.openSession(ADAPTER, true, false, Binder()) })
        bridge.revoke(PROBE)
        assertEquals(BridgeContract.END_ACCESS_REVOKED, calling(PROBE_UID) { first.endReason })
        assertEquals(0, access().getJSONArray("approved").length())
        assertThrows(SecurityException::class.java) { calling(PROBE_UID) { bridge.status } }

        approve(PROBE, PROBE_UID, PROBE_KEY)
        calling(SECOND_UID) { assertThrows(SecurityException::class.java) { bridge.status } }
        bridge.handOver(PROBE)
        val second = checkNotNull(calling(PROBE_UID) { bridge.openSession(ADAPTER, true, false, Binder()) })
        bridge.setOtherAppsAllowed(false)
        assertEquals(BridgeContract.END_ACCESS_REVOKED, calling(PROBE_UID) { second.endReason })
        val access = access()
        assertFalse(access.getBoolean("on"))
        assertEquals(0, access.getJSONArray("approved").length())
        assertEquals(0, access.getJSONArray("requests").length())
        bridge.setOtherAppsAllowed(true)
        assertEquals("turned on again, nothing came back", 0, access().getJSONArray("approved").length())
    }

    @Test
    fun `an approved app re-signed by another key is refused and asks again, key changed`() {
        approve(PROBE, PROBE_UID, PROBE_KEY)
        val newKey = ByteArray(32) { 0x77 }
        ShadowSigningPackageManager.signers[PROBE_UID] = newKey
        ShadowSigningPackageManager.packageSigners[PROBE] = newKey
        packageChanged(Intent.ACTION_PACKAGE_REPLACED, PROBE)
        calling(PROBE_UID) { assertThrows(SecurityException::class.java) { bridge.status } }
        val request = access().getJSONArray("requests").getJSONObject(0)
        assertEquals(fingerprint(newKey), request.getString("sha256"))
        assertTrue(request.getBoolean("keyChanged"))
        assertThrows("only a recorded key is approved", IllegalArgumentException::class.java) { bridge.approve(PROBE, fingerprint(SECOND_KEY)) }
        bridge.approve(PROBE, fingerprint(newKey))
        val approved = access().getJSONArray("approved")
        assertEquals("the new key replaced the old", 1, approved.length())
        assertEquals(fingerprint(newKey), approved.getJSONObject(0).getString("sha256"))
    }

    @Test
    fun `an app sharing its uid is refused and not recorded`() {
        bridge.setOtherAppsAllowed(true)
        shadowOf(controller.get().packageManager).setPackagesForUid(SHARED_UID, "com.example.a", "com.example.b")
        val refusal = calling(SHARED_UID) { assertThrows(SecurityException::class.java) { bridge.status } }
        assertTrue(refusal.message, refusal.message!!.contains("uid of its own"))
        assertEquals(0, access().getJSONArray("requests").length())
    }

    @Test
    fun `with Carlink gone or failing the pin, access is off and cleared`() {
        approve(PROBE, PROBE_UID, PROBE_KEY)
        bridge.handOver(PROBE)
        ShadowSigningPackageManager.packageSigners[BridgeContract.DISPLAY_PACKAGE] = ByteArray(32) { 0x5A }
        packageChanged(Intent.ACTION_PACKAGE_REPLACED, BridgeContract.DISPLAY_PACKAGE)
        var access = access()
        assertFalse(access.getBoolean("on"))
        assertFalse(access.getBoolean("carlinkVerified"))
        assertEquals(0, access.getJSONArray("approved").length())
        assertTrue(access.isNull("handedTo"))

        ShadowSigningPackageManager.packageSigners[BridgeContract.DISPLAY_PACKAGE] = ShadowSigningPackageManager.sha256(BuildConfig.CALLER_CERTS.split(',').first())
        approve(PROBE, PROBE_UID, PROBE_KEY)
        shadowOf(controller.get().packageManager).removePackage(BridgeContract.DISPLAY_PACKAGE)
        // Carlink still calls, by the uid pin, so its getAccess shows what the bridge did.
        shadowOf(controller.get().packageManager).setPackagesForUid(CARLINK_UID, BridgeContract.DISPLAY_PACKAGE)
        packageChanged(Intent.ACTION_PACKAGE_REMOVED, BridgeContract.DISPLAY_PACKAGE)
        val refusal = calling(PROBE_UID) { assertThrows(SecurityException::class.java) { bridge.status } }
        assertTrue("refused as with access off: ${refusal.message}", refusal.message!!.contains("is not ${BridgeContract.DISPLAY_PACKAGE}"))
        access = access()
        assertFalse(access.getBoolean("on"))
        assertEquals(0, access.getJSONArray("approved").length())
    }

    @Test
    fun `approvals outlive the bridge, a handover doesn't`() {
        approve(PROBE, PROBE_UID, PROBE_KEY)
        bridge.handOver(PROBE)
        controller.destroy()
        start()
        val access = access()
        assertTrue(access.getBoolean("on"))
        assertEquals(PROBE, access.getJSONArray("approved").getJSONObject(0).getString("package"))
        assertTrue(access.isNull("handedTo"))
        assertNull(calling(PROBE_UID) { bridge.openSession(ADAPTER, true, false, Binder()) })
    }

    @Test
    fun `an approved app's status names the adapters only, and whether it holds the adapter`() {
        approve(PROBE, PROBE_UID, PROBE_KEY)
        var status = calling(PROBE_UID) { bridge.status }
        assertTrue(status, status.contains("$ADAPTER 1314:2d00 perm=true"))
        assertFalse(status, status.contains(STICK))
        assertTrue(status, status.contains("holder=nobody handedToYou=false"))
        bridge.handOver(PROBE)
        calling(PROBE_UID) { checkNotNull(bridge.openSession(ADAPTER, true, false, Binder())) }
        status = calling(PROBE_UID) { bridge.status }
        assertTrue(status, status.contains("holder=you handedToYou=true"))
        assertTrue("Carlink's line is the full one", bridge.status.contains(STICK) && bridge.status.contains("access=on(approved=1"))
    }

    @Test
    fun `the access listener hears every change, and a dead one is dropped`() {
        val heard = Counter()
        val dead = Counter(dies = true)
        bridge.setAccessListener(heard)
        bridge.setAccessListener(dead)
        bridge.setOtherAppsAllowed(true)
        assertEquals(1, heard.calls)
        assertEquals(1, dead.calls)
        calling(PROBE_UID) { assertThrows(SecurityException::class.java) { bridge.status } }
        bridge.approve(PROBE, fingerprint(PROBE_KEY))
        bridge.handOver(PROBE)
        bridge.takeBack()
        assertEquals("request, approval, handover, take-back", 5, heard.calls)
        assertEquals("dropped after its first", 1, dead.calls)
    }

    private class Counter(
        private val dies: Boolean = false,
    ) : IBridgeAccessListener.Stub() {
        var calls = 0

        override fun onAccessChanged() {
            calls++
            if (dies) throw RemoteException("listener's process died")
        }
    }

    private fun start() {
        controller = Robolectric.buildService(BridgeService::class.java).create()
        ShadowBinder.setCallingUid(CARLINK_UID)
        bridge = ICarlinkBridge.Stub.asInterface(controller.get().onBind(Intent()))
    }

    private fun install(
        packageName: String,
        label: String,
        uid: Int,
        key: ByteArray,
    ) {
        val packages = shadowOf(controller.get().packageManager)
        packages.installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo =
                    ApplicationInfo().apply {
                        this.packageName = packageName
                        this.uid = uid
                        nonLocalizedLabel = label
                    }
            },
        )
        packages.setPackagesForUid(uid, packageName)
        ShadowSigningPackageManager.signers[uid] = key
        ShadowSigningPackageManager.packageSigners[packageName] = key
    }

    /** Access on, [packageName] asked once (as [uid]) and approved with [key]. */
    private fun approve(
        packageName: String,
        uid: Int,
        key: ByteArray,
    ) {
        bridge.setOtherAppsAllowed(true)
        calling(uid) { assertThrows(SecurityException::class.java) { bridge.status } }
        bridge.approve(packageName, fingerprint(key))
    }

    private fun packageChanged(
        action: String,
        packageName: String,
    ) {
        controller.get().sendBroadcast(Intent(action, Uri.parse("package:$packageName")))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun idle(time: Duration) {
        shadowOf(Looper.getMainLooper()).idleFor(time)
    }

    private fun access() = JSONObject(bridge.access)

    private fun <T> calling(
        uid: Int,
        block: () -> T,
    ): T {
        ShadowBinder.setCallingUid(uid)
        try {
            return block()
        } finally {
            ShadowBinder.setCallingUid(CARLINK_UID)
        }
    }

    private companion object {
        const val ADAPTER = "/dev/bus/usb/001/002"
        const val STICK = "/dev/bus/usb/001/003"
        const val CARLINK_UID = 10_123
        const val PROBE_UID = 10_500
        const val SECOND_UID = 10_501
        const val SHARED_UID = 10_502
        const val PROBE = "com.example.probe"
        const val SECOND = "com.example.second"
        val PROBE_KEY = ByteArray(32) { 0x11 }
        val SECOND_KEY = ByteArray(32) { 0x22 }
    }
}
