package com.enigy.carlink2.bridge

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Binder
import android.os.Parcel
import android.os.Parcelable
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import org.robolectric.util.ReflectionHelpers.callConstructor
import org.robolectric.util.ReflectionHelpers.callInstanceMethod

/**
 * A device with one interface of bulk IN 0x81 and bulk OUT 0x01, through UsbDevice's parcel: the SDK hides
 * the USB descriptors' constructors. After PR enigy/carlink#1's test (mammut3).
 */
internal fun usbDevice(
    name: String,
    vendorId: Int = 0x1314,
    productId: Int = 0x2d00,
): UsbDevice {
    val endpoints =
        listOf(0x81, 0x01)
            .map { address ->
                callConstructor(
                    UsbEndpoint::class.java,
                    from(Int::class.java, address),
                    from(Int::class.java, UsbConstants.USB_ENDPOINT_XFER_BULK),
                    from(Int::class.java, 512),
                    from(Int::class.java, 0),
                )
            }.toTypedArray<Parcelable>()
    val iface =
        callConstructor(
            UsbInterface::class.java,
            from(Int::class.java, 0),
            from(Int::class.java, 0),
            from(String::class.java, null),
            from(Int::class.java, 0xFF),
            from(Int::class.java, 0),
            from(Int::class.java, 0),
        )
    callInstanceMethod<Unit>(iface, "setEndpoints", from(Array<Parcelable>::class.java, endpoints))
    val config =
        callConstructor(
            UsbConfiguration::class.java,
            from(Int::class.java, 1),
            from(String::class.java, null),
            from(Int::class.java, 0),
            from(Int::class.java, 0),
        )
    callInstanceMethod<Unit>(config, "setInterfaces", from(Array<Parcelable>::class.java, arrayOf<Parcelable>(iface)))
    val parcel = Parcel.obtain()
    try {
        parcel.writeString(name)
        listOf(vendorId, productId, 0, 0, 0).forEach(parcel::writeInt)
        parcel.writeString("Carlinkit")
        parcel.writeString("OCBM adapter")
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
