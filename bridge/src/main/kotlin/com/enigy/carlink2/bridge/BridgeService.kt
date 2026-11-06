package com.enigy.carlink2.bridge

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log

/**
 * The display app binds here for the adapter. This service holds no state of its own beyond
 * the [UsbSession] and the [PermissionPrompt]. USB permission normally arrives from the
 * platform's fixed-handler grant; the prompt is only for an adapter that attached during boot
 * and so missed that grant ([205]).
 *
 * Every call but getApiVersion() is the display app's alone ([CallerVerifier]), a session
 * handle's ([Handle], API 5) included. From API 6 the owner may let apps it approves in Carlink use
 * openSession, their own handles, getStatus, hasPermission and requestPermission, while Carlink hands
 * the adapter over and on adapters only ([AccessControl]); off, the default, changes nothing.
 */
class BridgeService : Service() {
    private lateinit var usbManager: UsbManager
    private lateinit var usb: UsbSession
    private lateinit var callers: CallerVerifier
    private lateinit var prompt: PermissionPrompt
    private lateinit var apps: AccessControl

    /** Who called: Carlink, or an approved app (API 6). */
    private sealed interface Caller {
        data object Carlink : Caller

        data class App(
            val packageName: String,
        ) : Caller
    }

    private val detachReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                val device: UsbDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                usb.onDetached(device?.deviceName)
            }
        }

    // A package added, replaced or removed may be a caller under a new key, or Carlink gone: verify again.
    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                callers.forget()
                apps.forget()
                if (intent.data?.schemeSpecificPart == BridgeContract.DISPLAY_PACKAGE) apps.isOn()
            }
        }

    private val binder =
        object : ICarlinkBridge.Stub() {
            override fun getApiVersion(): Int = BridgeContract.API_VERSION

            override fun hasPermission(deviceName: String?): Boolean {
                if (enforceCaller(appsToo = true) is Caller.App && !apps.isAdapter(deviceName)) return false
                return usb.hasPermission(deviceName)
            }

            override fun open(deviceName: String?): ParcelFileDescriptor? {
                enforceCaller()
                apps.carlinkOpening()
                // Returned through AIDL with PARCELABLE_WRITE_RETURN_VALUE, which closes our copy.
                return usb.open(deviceName, onRequest = false, trace = false)?.takeStream()
            }

            override fun write(
                data: ByteArray?,
                timeoutMs: Int,
            ): Int {
                enforceCaller()
                return usb.write(data, timeoutMs)
            }

            override fun close() {
                enforceCaller()
                usb.close(BridgeContract.END_CLOSED, "closed by display app", onlyCarlinks = true)
            }

            override fun getStatus(): String =
                when (val caller = enforceCaller(appsToo = true)) {
                    is Caller.App -> apps.shortStatus(caller.packageName)
                    Caller.Carlink -> describe()
                }

            override fun requestPermission(deviceName: String?): Int {
                if (enforceCaller(appsToo = true) is Caller.App && !apps.isAdapter(deviceName)) return BridgeContract.PERMISSION_NO_DEVICE
                return prompt.request(deviceName)
            }

            override fun openOnRequest(
                deviceName: String?,
                trace: Boolean,
            ): Boolean {
                enforceCaller()
                apps.carlinkOpening()
                return usb.open(deviceName, onRequest = true, trace = trace) != null
            }

            override fun read(
                length: Int,
                timeoutMs: Int,
            ): ByteArray? {
                enforceCaller()
                return usb.read(length, timeoutMs)
            }

            override fun clearHalt(inEndpoint: Boolean): Int {
                enforceCaller()
                return usb.clearHalt(inEndpoint)
            }

            override fun resetBulkOnly(): Boolean {
                enforceCaller()
                return usb.resetBulkOnly()
            }

            override fun takeTrace(): String {
                enforceCaller()
                return usb.takeTrace()
            }

            override fun openSession(
                deviceName: String?,
                onRequest: Boolean,
                trace: Boolean,
                client: IBinder?,
            ): IUsbBridgeSession? {
                val caller = enforceCaller(appsToo = true)
                requireNotNull(client) { "openSession needs a Binder of the caller's own" }
                val owner =
                    when (caller) {
                        is Caller.App -> {
                            if (!apps.mayOpen(caller.packageName, deviceName)) return null
                            caller.packageName
                        }
                        Caller.Carlink -> {
                            apps.carlinkOpening()
                            BridgeContract.DISPLAY_PACKAGE
                        }
                    }
                val handle = Handle(client, Binder.getCallingUid(), owner)
                val onEnded: (String) -> Unit = { reason ->
                    handle.unwatch()
                    if (caller is Caller.App) apps.onAppSessionEnded(owner, reason)
                }
                handle.session = usb.open(deviceName, onRequest, trace, onEnded, owner) ?: return null
                if (!handle.watch()) {
                    val who = if (caller is Caller.App) owner else "the display app"
                    usb.end(handle.session, BridgeContract.END_CLIENT_GONE, "$who's process died while it opened $deviceName")
                    return null
                }
                if (caller is Caller.App && !apps.onAppOpened(caller.packageName, handle.session)) return null
                return handle
            }

            override fun getAccess(): String {
                enforceCaller()
                return apps.json()
            }

            override fun setOtherAppsAllowed(on: Boolean) {
                enforceCaller()
                apps.setOn(on)
            }

            override fun approve(
                packageName: String?,
                sha256: String?,
            ) {
                enforceCaller()
                apps.approve(requireNotNull(packageName), sha256)
            }

            override fun revoke(packageName: String?) {
                enforceCaller()
                apps.revoke(requireNotNull(packageName))
            }

            override fun dismissRequest(packageName: String?) {
                enforceCaller()
                apps.dismissRequest(requireNotNull(packageName))
            }

            override fun handOver(packageName: String?) {
                enforceCaller()
                apps.handOver(requireNotNull(packageName))
            }

            override fun takeBack() {
                enforceCaller()
                apps.takeBack()
            }

            override fun setAccessListener(listener: IBridgeAccessListener?) {
                enforceCaller()
                apps.setListener(listener)
            }
        }

    /**
     * API 5: the handle ICarlinkBridge.openSession returns, on one [UsbSession.Session]. Its calls act on that
     * session only (the bridge's API ≤ 4 calls act on whichever session is open) and are the display app's
     * alone, like the bridge's. It watches the client's Binder, so the session ends when the client's process
     * dies, and stops watching once the session has ended: Carlink opens a session per connect.
     *
     * API 6: a handle an approved app opened is that app's alone: its uid ([openerUid]). It needs no approval
     * check per call, as the session ends when the approval or the handover does, and the app can still ask
     * why it ended.
     */
    private inner class Handle(
        private val client: IBinder,
        private val openerUid: Int,
        private val owner: String,
    ) : IUsbBridgeSession.Stub(),
        IBinder.DeathRecipient {
        @Volatile lateinit var session: UsbSession.Session

        private val watchLock = Any()
        private var watching = false
        private var ended = false

        /** Watch the client's Binder; false when its process has already died. */
        fun watch(): Boolean =
            synchronized(watchLock) {
                if (ended) return true
                try {
                    client.linkToDeath(this, 0)
                    watching = true
                    true
                } catch (_: RemoteException) {
                    false
                }
            }

        /** The session ended: stop watching the client's Binder. */
        fun unwatch() {
            synchronized(watchLock) {
                ended = true
                if (!watching) return
                watching = false
                // Binder drops the link itself when the client dies.
                runCatching { client.unlinkToDeath(this, 0) }
            }
        }

        private fun enforceOwner() {
            if (owner == BridgeContract.DISPLAY_PACKAGE) {
                enforceCaller()
                return
            }
            val uid = Binder.getCallingUid()
            if (uid == openerUid) return
            val reason = "uid $uid didn't open this session; $owner did"
            Log.w(TAG, "Rejected caller: $reason")
            throw SecurityException(reason)
        }

        override fun binderDied() {
            usb.end(session, BridgeContract.END_CLIENT_GONE)
        }

        override fun takeStream(): ParcelFileDescriptor? {
            enforceOwner()
            // Returned through AIDL with PARCELABLE_WRITE_RETURN_VALUE, which closes our copy.
            return session.takeStream()
        }

        override fun write(
            data: ByteArray?,
            timeoutMs: Int,
        ): Int {
            enforceOwner()
            return session.write(data, timeoutMs)
        }

        override fun read(
            length: Int,
            timeoutMs: Int,
        ): ByteArray? {
            enforceOwner()
            return session.read(length, timeoutMs)
        }

        override fun clearHalt(inEndpoint: Boolean): Int {
            enforceOwner()
            return session.clearHalt(inEndpoint)
        }

        override fun resetBulkOnly(): Boolean {
            enforceOwner()
            return session.resetBulkOnly()
        }

        override fun takeTrace(): String {
            enforceOwner()
            return session.takeTrace()
        }

        override fun close() {
            enforceOwner()
            usb.end(session, BridgeContract.END_CLOSED, "closed by ${if (owner == BridgeContract.DISPLAY_PACKAGE) "display app" else owner}")
        }

        override fun getEndReason(): String? {
            enforceOwner()
            return session.endReason
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usb = UsbSession(usbManager)
        callers = CallerVerifier(this, BuildConfig.CALLER_CERTS)
        prompt = PermissionPrompt(this, usbManager).apply { register() }
        apps = AccessControl(this, usbManager, usb, callers)
        register(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        val packages =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
        register(packageReceiver, packages)
        BridgeStats.event("service created")
        Log.i(TAG, "Bridge service created: ${describe()}")
    }

    private fun register(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // Last client gone (display app stopped or died) — release the adapter.
        usb.close(BridgeContract.END_UNBOUND, "display app unbound")
        callers.forget()
        apps.forget()
        return false
    }

    override fun onDestroy() {
        usb.close(BridgeContract.END_BRIDGE_STOPPED, "bridge service destroyed")
        unregisterReceiver(detachReceiver)
        unregisterReceiver(packageReceiver)
        prompt.unregister()
        super.onDestroy()
    }

    /**
     * Carlink, by the pin; with [appsToo], an approved app too (API 6, access on). An approved app calling one of
     * Carlink's own calls is refused, and an app that isn't approved is recorded as a request ([AccessControl.check]).
     */
    private fun enforceCaller(appsToo: Boolean = false): Caller {
        val uid = Binder.getCallingUid()
        val carlinkRefusal = callers.rejectReason(uid) ?: return Caller.Carlink
        val reason =
            when (val verdict = apps.check(uid)) {
                AccessControl.Verdict.NotAnApp -> carlinkRefusal
                is AccessControl.Verdict.Refused -> verdict.reason
                is AccessControl.Verdict.Approved ->
                    if (appsToo) {
                        return Caller.App(verdict.packageName)
                    } else {
                        "${verdict.packageName} is approved for adapter sessions only (openSession, getStatus, hasPermission, requestPermission); this call is Carlink's"
                    }
            }
        Log.w(TAG, "Rejected caller: $reason")
        BridgeStats.event("rejected $reason")
        throw SecurityException(reason)
    }

    /**
     * Everything needed to diagnose a missing grant without adb: whether the platform still
     * routes attaches to this package, which Android user we run as (the grant goes to the
     * user that was current at attach time), and which devices we hold permission for.
     */
    private fun describe(): String {
        val devices =
            usbManager.deviceList.values.joinToString(prefix = "[", postfix = "]") { device ->
                "${device.deviceName} ${hex4(device.vendorId)}:${hex4(device.productId)} " +
                    "perm=${usbManager.hasPermission(device)}"
            }
        val openDevice = usb.openDeviceName
        val sessionText =
            when {
                openDevice != null -> "open($openDevice)"
                usb.lastError.isNotEmpty() -> "closed(last=${usb.lastCloseReason}; error=${usb.lastError})"
                else -> "closed(last=${usb.lastCloseReason})"
            }
        return "bridge api=${BridgeContract.API_VERSION} v=${BuildConfig.VERSION_NAME} " +
            "user=${Process.myUid() / PER_USER_RANGE} handler=${fixedHandlerComponent()} " +
            "devices=$devices session=$sessionText ${apps.describe()} ${prompt.describe()} ${BridgeStats.describe()}"
    }

    @SuppressLint("DiscouragedApi")
    private fun fixedHandlerComponent(): String {
        val resources = Resources.getSystem()
        val id = resources.getIdentifier("config_UsbDeviceConnectionHandling_component", "string", "android")
        if (id == 0) return "<no such resource>"
        return resources.getString(id).ifEmpty { "<empty>" }
    }

    private fun hex4(value: Int): String = value.toString(16).padStart(4, '0')

    private companion object {
        const val TAG = "Carlink2Bridge"

        // UserHandle.PER_USER_RANGE (hidden): uid = userId * 100000 + appId.
        const val PER_USER_RANGE = 100_000
    }
}
