package com.enigy.carlink2.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * API 6: other apps' access to the adapter, the owner's opt-in. Off by default, and then nothing here changes
 * what the bridge does: it serves Carlink alone ([CallerVerifier]).
 *
 * On, an app that calls is approved when its uid is its package's alone and the package is signed by the key
 * the owner approved; one that isn't is refused and recorded as a request, which is the only thing the owner
 * approves from. An approved app still gets the adapter only while Carlink hands it over ([handOver]), only an
 * adapter ([BridgeContract.ADAPTER_IDS]), and only through a session handle. The handover ends when Carlink
 * takes it back or opens anything, when the owner removes the app or turns access off, when the app's session
 * client dies, and after [BridgeContract.HANDOVER_GRACE_MS] without a session of the app's.
 *
 * Approvals count only while the installed Carlink passes the pin by package ([CallerVerifier.displayAppPasses]):
 * otherwise access is off and cleared. Approvals and requests are kept ([AccessStore]); the handover is not.
 *
 * Locks: [lock] is never held across a call into [UsbSession] that takes its lock, so a session's end, which
 * runs [onAppSessionEnded] under that lock, never waits on this one held the other way round.
 */
internal class AccessControl(
    private val context: Context,
    private val usbManager: UsbManager,
    private val usb: UsbSession,
    private val callers: CallerVerifier,
    private val store: AccessStore = AccessStore(context),
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    sealed interface Verdict {
        /** Access is off, or the caller is (or claims to be) Carlink: the pin's answer stands. */
        data object NotAnApp : Verdict

        data class Approved(
            val packageName: String,
        ) : Verdict

        data class Refused(
            val reason: String,
        ) : Verdict
    }

    data class Handover(
        val packageName: String,
        val since: Long,
    )

    private val lock = Any()

    // Approved verdicts, uid → package; cleared on any package change, an unbind, a removal or access turned off.
    private val approvedUids = ConcurrentHashMap<Int, String>()

    @Volatile private var carlinkPasses: Boolean? = null

    @Volatile var handedTo: Handover? = null
        private set

    // Uptime since which the app the adapter is handed to has had no session.
    private var idleSince = 0L

    private val listeners = CopyOnWriteArrayList<IBridgeAccessListener>()

    private val graceCheck = Runnable { expireIdleHandover() }

    // The requests, held here and written through to [store], except a refused app's repeat calls (count, lastAt):
    // those are written at most every [REQUEST_WRITE_MS] per package, so an app retrying in a loop doesn't rewrite
    // the preferences on every call. Guarded by [lock]; [cachedRequests] is loaded on first use.
    private var cachedRequests: List<AccessRequest>? = null
    private val lastRequestWrite = HashMap<String, Long>()
    private var requestsDirty = false
    private var flushPending = false
    private val flushRequests =
        Runnable {
            synchronized(lock) {
                flushPending = false
                if (requestsDirty) writeRequestsLocked(requestsLocked())
            }
        }

    private fun requestsLocked(): List<AccessRequest> = cachedRequests ?: store.requests.also { cachedRequests = it }

    private fun writeRequestsLocked(value: List<AccessRequest>) {
        cachedRequests = value
        store.requests = value
        requestsDirty = false
    }

    /** On, and the installed Carlink passes the pin. When it doesn't, access is turned off and cleared here. */
    fun isOn(): Boolean {
        if (!store.on) return false
        val passes = carlinkPasses ?: callers.displayAppPasses().also { carlinkPasses = it }
        if (passes) return true
        Log.w(TAG, "Other apps' access turned off: ${BridgeContract.DISPLAY_PACKAGE} is missing or fails the pin")
        turnOff("${BridgeContract.DISPLAY_PACKAGE} is missing or fails the pin")
        return false
    }

    /** Whether [uid], which isn't Carlink by the pin, is an approved app; a refusal is recorded as a request. */
    fun check(uid: Int): Verdict {
        if (!isOn()) return Verdict.NotAnApp
        approvedUids[uid]?.let { return Verdict.Approved(it) }
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)?.toList().orEmpty()
        if (BridgeContract.DISPLAY_PACKAGE in packages) return Verdict.NotAnApp
        val packageName = packages.singleOrNull() ?: return Verdict.Refused("uid $uid is $packages: the bridge serves only an app with a uid of its own")
        val approval = store.approved.find { it.packageName == packageName }
        val key = approval?.let { parseFingerprint(it.sha256) }
        if (key != null && pm.hasSigningCertificate(uid, key, PackageManager.CERT_INPUT_SHA256)) {
            approvedUids[uid] = packageName
            Log.i(TAG, "Accepting $packageName (uid $uid), approved, signed by ${approval.sha256}")
            return Verdict.Approved(packageName)
        }
        record(pm, packageName, approval)
        return Verdict.Refused(
            "$packageName isn't approved for the Carlink USB bridge. The owner approves it in Carlink › Settings › Bridge Configuration.",
        )
    }

    private fun record(
        pm: PackageManager,
        packageName: String,
        approval: Approval?,
    ) {
        // Without the signer there is nothing the owner could approve.
        val sha256 = PackageFacts.signer(pm, packageName)?.let(::fingerprint) ?: return
        val label = PackageFacts.label(pm, packageName)
        val installer = PackageFacts.installer(pm, packageName)
        val keyChanged = approval != null && approval.sha256 != sha256
        val now = System.currentTimeMillis()
        val isNew: Boolean
        synchronized(lock) {
            val all = requestsLocked()
            val old = all.find { it.packageName == packageName && it.sha256 == sha256 }
            isNew = old == null || old.keyChanged != keyChanged
            val request =
                old?.copy(label = label, installer = installer, lastAt = now, count = old.count + 1, keyChanged = keyChanged)
                    ?: AccessRequest(packageName, label, sha256, installer, now, now, 1, keyChanged)
            val next = (all.filterNot { it === old } + request).sortedBy { it.lastAt }.takeLast(BridgeContract.MAX_ACCESS_REQUESTS)
            val uptime = SystemClock.uptimeMillis()
            val lastWrite = lastRequestWrite[packageName]
            if (isNew || lastWrite == null || uptime - lastWrite >= REQUEST_WRITE_MS) {
                lastRequestWrite[packageName] = uptime
                writeRequestsLocked(next)
            } else {
                cachedRequests = next
                requestsDirty = true
                if (!flushPending) {
                    flushPending = true
                    handler.postDelayed(flushRequests, REQUEST_WRITE_MS)
                }
            }
        }
        if (isNew) {
            BridgeStats.event("access request from $packageName${if (keyChanged) " (key changed)" else ""}")
            notifyListeners()
        }
    }

    fun setOn(on: Boolean) {
        if (!on) {
            turnOff("the owner turned other apps' access off")
            return
        }
        if (store.on) return
        store.on = true
        carlinkPasses = null
        BridgeStats.event("access on")
        Log.i(TAG, "Other apps' access turned on")
        notifyListeners()
    }

    private fun turnOff(detail: String) {
        synchronized(lock) {
            store.clear()
            cachedRequests = emptyList()
            requestsDirty = false
            lastRequestWrite.clear()
            approvedUids.clear()
        }
        if (!endHandover(BridgeContract.END_ACCESS_REVOKED, detail)) endAppSession(null, BridgeContract.END_ACCESS_REVOKED, detail)
        BridgeStats.event("access off: $detail")
        Log.i(TAG, "Other apps' access off: $detail")
        notifyListeners()
    }

    fun approve(
        packageName: String,
        sha256: String?,
    ) {
        check(isOn()) { "Other apps' access is off" }
        val wanted = parseFingerprint(sha256)?.let(::fingerprint)
        requireNotNull(wanted) { "$sha256 isn't a SHA-256 fingerprint" }
        val replaced: Approval?
        synchronized(lock) {
            val request = requestsLocked().find { it.packageName == packageName && it.sha256 == wanted }
            requireNotNull(request) { "No request from $packageName signed by $wanted: open the app and let it connect first" }
            replaced = store.approved.find { it.packageName == packageName }
            store.approved = store.approved.filterNot { it.packageName == packageName } +
                Approval(packageName, request.label, wanted, request.installer, System.currentTimeMillis(), 0L, 0)
            writeRequestsLocked(requestsLocked().filterNot { it.packageName == packageName })
            approvedUids.values.removeIf { it == packageName }
        }
        if (replaced != null && replaced.sha256 != wanted) {
            // The app it was handed to was signed by the old key: another app now.
            if (handedTo?.packageName == packageName) endHandover(BridgeContract.END_ACCESS_REVOKED, "$packageName approved under another key")
        }
        BridgeStats.event("approved $packageName")
        Log.i(TAG, "Approved $packageName signed by $wanted")
        notifyListeners()
    }

    fun revoke(packageName: String) {
        synchronized(lock) {
            store.approved = store.approved.filterNot { it.packageName == packageName }
            approvedUids.values.removeIf { it == packageName }
        }
        val detail = "the owner removed $packageName"
        if (handedTo?.packageName != packageName || !endHandover(BridgeContract.END_ACCESS_REVOKED, detail)) {
            endAppSession(packageName, BridgeContract.END_ACCESS_REVOKED, detail)
        }
        BridgeStats.event("removed $packageName")
        notifyListeners()
    }

    fun dismissRequest(packageName: String) {
        synchronized(lock) { writeRequestsLocked(requestsLocked().filterNot { it.packageName == packageName }) }
        notifyListeners()
    }

    /** Hand the adapter to [packageName]; IllegalStateException with why when it can't be. */
    fun handOver(packageName: String) {
        check(isOn()) { "Other apps' access is off" }
        check(store.approved.any { it.packageName == packageName }) { "$packageName isn't approved" }
        val open = usb.current
        check(open == null || open.owner != BridgeContract.DISPLAY_PACKAGE) {
            "Carlink's session on ${open?.deviceName} is still open: Carlink closes it before it hands the adapter over"
        }
        val other: UsbSession.Session?
        synchronized(lock) {
            handedTo = Handover(packageName, System.currentTimeMillis())
            idleSince = SystemClock.uptimeMillis()
            other = usb.current?.takeIf { it.owner != packageName && it.owner != BridgeContract.DISPLAY_PACKAGE }
        }
        other?.let { usb.end(it, BridgeContract.END_TAKEN_BACK, "the adapter was handed to $packageName instead") }
        scheduleGrace(BridgeContract.HANDOVER_GRACE_MS)
        BridgeStats.event("handed over to $packageName")
        Log.i(TAG, "Adapter handed over to $packageName")
        notifyListeners()
    }

    /** Carlink takes the adapter back; nothing when it wasn't handed over. */
    fun takeBack(detail: String = "Carlink took the adapter back") {
        endHandover(BridgeContract.END_TAKEN_BACK, detail)
    }

    /** An open of Carlink's, any: it takes the adapter back first. */
    fun carlinkOpening() {
        if (handedTo != null) takeBack("Carlink opened the adapter")
    }

    /** Whether [packageName] may open [deviceName] now: the adapter is handed to it, and the device is an adapter. */
    fun mayOpen(
        packageName: String,
        deviceName: String?,
    ): Boolean = isOn() && handedTo?.packageName == packageName && isAdapter(deviceName)

    fun isAdapter(deviceName: String?): Boolean = deviceName?.let { usbManager.deviceList[it] }?.let { BridgeContract.isAdapter(it.vendorId, it.productId) } == true

    /** [session] opened for [packageName]. False, and the session ended, when the handover ended meanwhile. */
    fun onAppOpened(
        packageName: String,
        session: UsbSession.Session,
    ): Boolean {
        val stale: Boolean
        synchronized(lock) {
            stale = handedTo?.packageName != packageName
            if (!stale) {
                val now = System.currentTimeMillis()
                store.approved = store.approved.map { if (it.packageName == packageName) it.copy(lastUsedAt = now, sessions = it.sessions + 1) else it }
            }
        }
        if (stale) {
            usb.end(session, BridgeContract.END_TAKEN_BACK, "the handover to $packageName ended while it opened")
            return false
        }
        handler.removeCallbacks(graceCheck)
        notifyListeners()
        return true
    }

    /** An app's session ended: its client died (the handover ends), or the grace starts again. Runs under UsbSession's lock. */
    fun onAppSessionEnded(
        packageName: String,
        reason: String,
    ) {
        val handover = handedTo
        if (handover?.packageName == packageName) {
            if (reason == BridgeContract.END_CLIENT_GONE) {
                endHandover(BridgeContract.END_CLIENT_GONE, "$packageName's process died")
                return
            }
            synchronized(lock) { idleSince = SystemClock.uptimeMillis() }
            scheduleGrace(BridgeContract.HANDOVER_GRACE_MS)
        }
        notifyListeners()
    }

    private fun expireIdleHandover() {
        val handover = handedTo ?: return
        if (usb.current?.owner == handover.packageName) return
        val idle = SystemClock.uptimeMillis() - synchronized(lock) { idleSince }
        if (idle >= BridgeContract.HANDOVER_GRACE_MS) {
            endHandover(BridgeContract.END_TAKEN_BACK, "${handover.packageName} had no session for ${BridgeContract.HANDOVER_GRACE_MS / MS_PER_S} s")
        } else {
            scheduleGrace(BridgeContract.HANDOVER_GRACE_MS - idle)
        }
    }

    private fun scheduleGrace(delayMs: Long) {
        handler.removeCallbacks(graceCheck)
        handler.postDelayed(graceCheck, delayMs)
    }

    /** End the handover, and the app's session with [reason]; false when there was none. */
    private fun endHandover(
        reason: String,
        detail: String,
    ): Boolean {
        val ended: Handover
        synchronized(lock) {
            ended = handedTo ?: return false
            handedTo = null
        }
        handler.removeCallbacks(graceCheck)
        endAppSession(ended.packageName, reason, detail)
        BridgeStats.event("handover to ${ended.packageName} ended: $detail")
        Log.i(TAG, "Handover to ${ended.packageName} ended: $detail")
        notifyListeners()
        return true
    }

    /** End the open session of [packageName]'s, or of any app's when null. */
    private fun endAppSession(
        packageName: String?,
        reason: String,
        detail: String,
    ) {
        val session = usb.current ?: return
        val isApps = session.owner != BridgeContract.DISPLAY_PACKAGE && (packageName == null || session.owner == packageName)
        if (isApps) usb.end(session, reason, detail)
    }

    /** Forget the verdicts: a package changed, or every client unbound. */
    fun forget() {
        approvedUids.clear()
        carlinkPasses = null
    }

    fun setListener(listener: IBridgeAccessListener?) {
        if (listener == null) {
            listeners.clear()
            return
        }
        val binder = listener.asBinder()
        if (listeners.any { it.asBinder() == binder }) return
        try {
            binder.linkToDeath(DropOnDeath(listener), 0)
        } catch (_: RemoteException) {
            return
        }
        listeners += listener
    }

    private inner class DropOnDeath(
        private val listener: IBridgeAccessListener,
    ) : IBinder.DeathRecipient {
        override fun binderDied() {
            listeners.remove(listener)
        }
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            try {
                listener.onAccessChanged()
            } catch (e: RemoteException) {
                Log.w(TAG, "Dropping a dead access listener: ${e.message}")
                listeners.remove(listener)
            }
        }
    }

    /** getAccess(): see ICarlinkBridge.aidl. */
    fun json(): String {
        val on = isOn()
        return JSONObject()
            .put("v", 1)
            .put("on", on)
            .put("carlinkVerified", carlinkPasses ?: callers.displayAppPasses())
            .put("approved", JSONArray(store.approved.map { it.toJson() }))
            // Kept oldest first ([record]); newest first here.
            .put("requests", JSONArray(synchronized(lock) { requestsLocked() }.asReversed().map { it.toJson() }))
            .put("handedTo", handedTo?.let { JSONObject().put("package", it.packageName).put("since", it.since) } ?: JSONObject.NULL)
            .put("holder", usb.current?.owner ?: JSONObject.NULL)
            .toString()
    }

    /** For Carlink's status line. */
    fun describe(): String {
        if (!store.on) return "access=off"
        val handover = handedTo?.let { " handedTo=${it.packageName}" }.orEmpty()
        return "access=on(approved=${store.approved.size} requests=${synchronized(lock) { requestsLocked() }.size}$handover)"
    }

    /** getStatus() for an approved app: the versions, the adapters only, who holds the adapter, and whether it is handed to the caller. */
    fun shortStatus(packageName: String): String {
        val adapters =
            usbManager.deviceList.values
                .filter { BridgeContract.isAdapter(it.vendorId, it.productId) }
                .joinToString(prefix = "[", postfix = "]") { "${it.deviceName} ${hex4(it.vendorId)}:${hex4(it.productId)} perm=${usbManager.hasPermission(it)}" }
        val holder =
            when (val owner = usb.current?.owner) {
                null -> "nobody"
                packageName -> "you"
                BridgeContract.DISPLAY_PACKAGE -> "Carlink"
                else -> "another app"
            }
        return "bridge api=${BridgeContract.API_VERSION} v=${BuildConfig.VERSION_NAME} adapters=$adapters holder=$holder " +
            "handedToYou=${handedTo?.packageName == packageName}"
    }

    private fun hex4(value: Int): String = value.toString(16).padStart(4, '0')

    private companion object {
        const val TAG = "Carlink2Bridge"
        const val MS_PER_S = 1_000

        // How often a refused app's repeat calls may rewrite the requests (count, lastAt) per package.
        const val REQUEST_WRITE_MS = 5_000L
    }
}
