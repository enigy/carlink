package com.enigy.carlink2.bridge

import android.content.Intent
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import org.robolectric.util.ReflectionHelpers.callConstructor
import org.robolectric.util.ReflectionHelpers.callInstanceMethod
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

/** Exercises generic callers, USB ownership, and teardown through the bridge service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class BridgeServiceTest {
    @Test
    fun `an unrelated app can bind and use the bridge without certificate registration`() {
        val controller = Robolectric.buildService(BridgeService::class.java).create()
        val bridge = ICarlinkBridge.Stub.asInterface(controller.get().onBind(Intent()))
        try {
            ShadowBinder.setCallingUid(12099)
            assertEquals(BridgeContract.API_VERSION, bridge.apiVersion)
            assertTrue(bridge.status.isNotBlank())
            assertFalse(bridge.hasPermission("/missing-device"))
            assertNull(bridge.open("/missing-device"))
            assertEquals(-1, bridge.write(byteArrayOf(1), 10))
            assertEquals(BridgeContract.PERMISSION_NO_DEVICE, bridge.requestPermission("/missing-device"))
            assertFalse(bridge.openOnRequest("/missing-device", false))
            assertNull(bridge.read(1, 10))
            assertEquals(-1, bridge.clearHalt(true))
            assertFalse(bridge.resetBulkOnly())
            assertEquals("", bridge.takeTrace())
            bridge.close()
        } finally {
            ShadowBinder.reset()
            controller.destroy()
        }
    }

    @Test
    fun `another app can use the bridge after the previous app unbinds`() {
        val controller = Robolectric.buildService(BridgeService::class.java).create()
        val service = controller.get()
        try {
            ShadowBinder.setCallingUid(12001)
            ICarlinkBridge.Stub.asInterface(service.onBind(Intent())).close()
            assertFalse(service.onUnbind(Intent()))
            ShadowBinder.setCallingUid(12002)
            val next = ICarlinkBridge.Stub.asInterface(service.onBind(Intent()))
            assertTrue(next.status.isNotBlank())
            assertFalse(next.hasPermission("/missing-device"))
        } finally {
            ShadowBinder.reset()
            controller.destroy()
        }
    }

    @Test
    fun `a superseded session keeps its end reason and cannot write to the new session`() {
        withAdapter { _, bridge, device ->
            ShadowBinder.setCallingUid(12001)
            val token = Binder()
            val first = checkNotNull(bridge.openSession(device.deviceName, true, false, token))
            val firstDeath = Shadow.extract<ShadowBinder>(token).deathRecipients.single()
            assertEquals(1, first.write(byteArrayOf(1), 10))
            assertNull(bridge.openSession("/missing-device", true, false, Binder()))
            assertNull(first.endReason)

            ShadowBinder.setCallingUid(12002)
            val second = checkNotNull(bridge.openSession(device.deviceName, true, false, Binder()))
            assertEquals(BridgeContract.END_HANDOFF, first.endReason)
            assertEquals(-1, first.write(byteArrayOf(2), 10))
            assertNull(first.read(1, 10))
            assertFalse(first.resetBulkOnly())
            first.close()
            firstDeath.binderDied()
            assertEquals(1, second.write(byteArrayOf(3), 10))
            assertNull(second.endReason)
            second.close()
            assertEquals(BridgeContract.END_CLOSED, second.endReason)
            assertEquals(BridgeContract.END_HANDOFF, first.endReason)
        }
    }

    @Test
    fun `a legacy caller cannot reclaim or close another caller's session`() {
        withAdapter { _, bridge, device ->
            ShadowBinder.setCallingUid(12001)
            ShadowBinder.setCallingPid(310)
            assertTrue(bridge.openOnRequest(device.deviceName, false))
            assertEquals(1, bridge.write(byteArrayOf(1), 10))
            ShadowBinder.setCallingPid(311)
            assertFalse(bridge.openOnRequest(device.deviceName, false))
            assertEquals(-1, bridge.write(byteArrayOf(2), 10))
            bridge.close()
            ShadowBinder.setCallingUid(12002)
            val scoped = checkNotNull(bridge.openSession(device.deviceName, true, false, Binder()))
            ShadowBinder.setCallingUid(12001)
            ShadowBinder.setCallingPid(310)
            assertFalse(bridge.openOnRequest(device.deviceName, false))
            assertEquals(-1, bridge.write(byteArrayOf(2), 10))
            bridge.close()
            assertEquals(1, scoped.write(byteArrayOf(3), 10))
            scoped.close()
            assertTrue(bridge.openOnRequest(device.deviceName, false))
        }
    }

    @Test
    fun `client death and USB detach report different end reasons`() {
        withAdapter { service, bridge, device ->
            val token = Binder()
            val first = checkNotNull(bridge.openSession(device.deviceName, true, false, token))
            Shadow.extract<ShadowBinder>(token).deathRecipients.single().binderDied()
            assertEquals(BridgeContract.END_CLIENT_GONE, first.endReason)

            val second = checkNotNull(bridge.openSession(device.deviceName, true, false, Binder()))
            service.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, device))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(BridgeContract.END_DETACHED, second.endReason)
            assertEquals(-1, second.write(byteArrayOf(1), 10))
        }
    }

    private fun withAdapter(run: (BridgeService, ICarlinkBridge, UsbDevice) -> Unit) {
        val controller = Robolectric.buildService(BridgeService::class.java).create()
        val service = controller.get()
        val usb = checkNotNull(service.getSystemService(UsbManager::class.java))
        val device = testDevice()
        shadowOf(usb).addOrUpdateUsbDevice(device, true)
        try {
            run(service, ICarlinkBridge.Stub.asInterface(service.onBind(Intent())), device)
        } finally {
            ShadowBinder.reset()
            controller.destroy()
        }
    }

    // The Android SDK hides the constructors for USB descriptors supplied by the platform.
    private fun testDevice(): UsbDevice {
        val endpoints = listOf(0x81, 0x01).map { address ->
            callConstructor(
                UsbEndpoint::class.java, from(Int::class.java, address),
                from(Int::class.java, UsbConstants.USB_ENDPOINT_XFER_BULK), from(Int::class.java, 512), from(Int::class.java, 0),
            )
        }.toTypedArray<Parcelable>()
        val iface = callConstructor(
            UsbInterface::class.java, from(Int::class.java, 0), from(Int::class.java, 0),
            from(String::class.java, null), from(Int::class.java, 0), from(Int::class.java, 0), from(Int::class.java, 0),
        )
        callInstanceMethod<Unit>(iface, "setEndpoints", from(Array<Parcelable>::class.java, endpoints))
        val config = callConstructor(
            UsbConfiguration::class.java, from(Int::class.java, 1), from(String::class.java, null),
            from(Int::class.java, 0), from(Int::class.java, 0),
        )
        callInstanceMethod<Unit>(config, "setInterfaces", from(Array<Parcelable>::class.java, arrayOf<Parcelable>(iface)))
        val parcel = Parcel.obtain()
        try {
            parcel.writeString("/dev/bus/usb/001/001")
            listOf(0x1314, 0x2d00, 0, 0, 0).forEach(parcel::writeInt)
            parcel.writeString("Test")
            parcel.writeString("USB adapter")
            parcel.writeString("1.0")
            parcel.writeStrongBinder(Binder())
            parcel.writeParcelableArray(arrayOf(config), 0)
            repeat(5) { parcel.writeInt(0) }
            parcel.setDataPosition(0)
            return UsbDevice.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
