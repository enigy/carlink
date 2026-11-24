package com.carlink.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.enigy.carlink2.bridge.BridgeContract

/**
 * USB direct: Android's own USB permission prompt, asked by Carlink itself, for head units where it works (the owner,
 * 24 Nov). The bridge's rules ([UsbPermissionWait.Port], bridge PermissionPrompt): one prompt per device instance, and
 * a denial sticks until the adapter re-enumerates (a new /dev/bus/usb path), so Deny never starts a prompt loop.
 *
 * On the GM head unit the system's USB handler package is missing (docs/ANDROID_AND_AAOS.md): the prompt may never show
 * and nothing answers, which [UsbPermissionWait] reads as still pending; Carlink then says so and points to Wi-Fi.
 */
class DirectUsbPermission(
    context: Context,
) {
    private val app = context.applicationContext
    private val usb: UsbManager? = app.getSystemService(UsbManager::class.java)
    private val lock = Any()
    private var askedFor: String? = null
    private var denied = false
    private var registered = false

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != ACTION_RESULT) return
                val name = synchronized(lock) { askedFor } ?: return
                // The platform's state, not the extras: before API 33 this receiver is exported.
                val granted = usb?.deviceList?.get(name)?.let { usb.hasPermission(it) } ?: false
                synchronized(lock) { if (name == askedFor) denied = !granted }
                logInfo("[USB_DIRECT] Android's USB prompt for $name: ${if (granted) "allowed" else "denied"}", tag = Logger.Tags.USB)
            }
        }

    /** The [UsbPermissionWait] port for the device at [deviceName]. */
    fun port(deviceName: String): UsbPermissionWait.Port =
        object : UsbPermissionWait.Port {
            override fun hasPermission(): Boolean = usb?.deviceList?.get(deviceName)?.let { usb.hasPermission(it) } ?: false

            override fun requestPermission(): Int = request(deviceName)
        }

    /** A BridgeContract.PERMISSION_* value: the first call for a device instance shows the prompt, later ones report it. */
    fun request(deviceName: String): Int {
        val u = usb ?: return BridgeContract.PERMISSION_NO_DEVICE
        val device = u.deviceList[deviceName] ?: return BridgeContract.PERMISSION_NO_DEVICE
        if (u.hasPermission(device)) return BridgeContract.PERMISSION_GRANTED
        synchronized(lock) {
            if (deviceName == askedFor) return if (denied) BridgeContract.PERMISSION_DENIED else BridgeContract.PERMISSION_PENDING
            askedFor = deviceName
            denied = false
        }
        register()
        // MUTABLE: the platform adds EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED to the result; the package makes it explicit.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val result = PendingIntent.getBroadcast(app, 0, Intent(ACTION_RESULT).setPackage(app.packageName), mutable or PendingIntent.FLAG_UPDATE_CURRENT)
        u.requestPermission(device, result)
        logInfo("[USB_DIRECT] asked Android for USB access to $deviceName", tag = Logger.Tags.USB)
        return BridgeContract.PERMISSION_PENDING
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // pre-33 has no flag; onReceive reads the platform's state instead
    private fun register() {
        synchronized(lock) {
            if (registered) return
            registered = true
        }
        val filter = IntentFilter(ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
    }

    private companion object {
        const val ACTION_RESULT = "com.carlink.USB_DIRECT_PERMISSION_RESULT"
    }
}
