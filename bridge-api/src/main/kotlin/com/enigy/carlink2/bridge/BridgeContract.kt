package com.enigy.carlink2.bridge

/**
 * Constants shared by the display app and the sideloaded USB bridge.
 *
 * Why two apps: on AAOS the platform hands every USB host attach to a fixed handler package
 * (`config_UsbDeviceConnectionHandling_component` =
 * `android.car.usb.handler/android.car.usb.handler.UsbHostManagementActivity` in the car product
 * overlay). UsbProfileGroupSettingsManager.deviceAttachedForFixedHandler() grants that package's
 * UID permission for the device and then tries to start the activity. GM ships no app with that
 * package name, so a sideloaded app that takes it receives every grant without a dialog. The
 * display app has to stay Play-installed to keep distraction-optimized display while driving,
 * so it cannot take that name itself.
 *
 * The bridge also claims the Templates Host's ClusterIconContentProvider authority, which the
 * host calls but never registers, so cluster maneuver icons reach the HUD for a Play-installed
 * display app.
 */
object BridgeContract {
    /** Bump when ICarlinkBridge gains methods or changes semantics; the display app checks [MIN_API_VERSION]. */
    const val API_VERSION = 7

    /**
     * Carlink3: from this API the bridge relays the adapter's bulk stream RAW — chunks as read from
     * the IN endpoint, no header parsing, no message boundaries. The display app's OCBM client does
     * its own framing (`Reassembler`). Older bridges (Carlink2, API ≤ 2) parsed the stock 0x55AA55AA
     * message framing and must not be used with an OCBM adapter.
     */
    const val RAW_STREAM_API_VERSION = 3

    /**
     * From this API (bridge 1.0.6) the bridge also opens a device for transfers on request
     * (openOnRequest, read, clearHalt, resetBulkOnly, takeTrace): no read pump, reads of an exact
     * length only when asked. USB mass storage (Bulk-Only Transport) needs that, for the stick a
     * stock adapter shares during its conversion: a stick stalls or loses data under a free-running
     * 16 KiB read (bench, 29 Oct). The adapter itself still uses open() and the pump.
     */
    const val ON_REQUEST_API_VERSION = 4

    /**
     * From this API (bridge 1.0.7) ICarlinkBridge.openSession opens a session with a handle of its own
     * (IUsbBridgeSession). The handle's calls act on that session only, fail once it has ended and never
     * reach the session after it, and its getEndReason says why it ended (one of the END_ values below).
     * The bridge also ends a session when the process that opened it dies. Ending a session never waits
     * out a transfer in flight, and every transfer's timeout is bounded ([MAX_TRANSFER_TIMEOUT_MS]).
     */
    const val SESSION_API_VERSION = 5

    /**
     * From this API (bridge 1.0.8) the owner may let other apps use the adapter (off by default: then the
     * bridge is Carlink's alone, exactly as before). An app gets in only once the owner approved it, by
     * package and signing certificate, in Carlink, and only while Carlink hands the adapter over to it
     * (ICarlinkBridge.handOver); any open of Carlink's takes it back. An approved app may use openSession,
     * its session handles, getStatus (a short form), hasPermission and requestPermission, on adapters
     * only ([ADAPTER_IDS]). The access calls (getAccess … setAccessListener) are Carlink's alone.
     */
    const val ACCESS_API_VERSION = 6

    /**
     * From this API (bridge 1.0.9) the bridge updates itself when Carlink asks (ICarlinkBridge.installUpdate, the owner's
     * tap in Settings, parked): it downloads the APK Carlink pins from [UPDATE_URL_PREFIX], checks its size, SHA-256,
     * package name and version code, and installs it with PackageInstaller. Carlink never touches the APK. The update
     * calls are Carlink's alone.
     */
    const val UPDATE_API_VERSION = 7

    /** Where an update may come from: a GitHub release download of enigy/carlink. */
    const val UPDATE_URL_PREFIX = "https://github.com/enigy/carlink/releases/download/"

    /**
     * The hosts GitHub redirects a release download to: release-assets.githubusercontent.com since 2025, and
     * objects.githubusercontent.com before. An update follows a redirect only to one of these, over HTTPS.
     */
    val UPDATE_REDIRECT_HOSTS: Set<String> = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")

    // installUpdate's request, a JSON object: the version Carlink pins.
    const val UPDATE_VERSION = "version"
    const val UPDATE_VERSION_CODE = "versionCode"
    const val UPDATE_URL = "url"
    const val UPDATE_SIZE = "size"
    const val UPDATE_SHA256 = "sha256"

    // IBridgeUpdateListener.onState steps.
    const val UPDATE_DOWNLOADING = "downloading"
    const val UPDATE_CHECKING = "checking"
    const val UPDATE_INSTALLING = "installing"

    /** IBridgeUpdateListener.onResult's message for a call while another update runs. */
    const val UPDATE_BUSY = "busy"

    /** USB vendor:product ids of the devices an approved app may open: Carlinkit adapters. */
    val ADAPTER_IDS: Set<Pair<Int, Int>> = setOf(0x1314 to 0x2d00, 0x1314 to 0x1520, 0x1314 to 0x1521)

    fun isAdapter(
        vendorId: Int,
        productId: Int,
    ): Boolean = (vendorId to productId) in ADAPTER_IDS

    /** A handover with no session of the app's this long ends by itself: the app never came, or left. */
    const val HANDOVER_GRACE_MS = 60_000L

    /** Requests the bridge keeps (getAccess "requests"); the oldest is dropped for a newer one. */
    const val MAX_ACCESS_REQUESTS = 8

    // IUsbBridgeSession.getEndReason results: why a session ended.

    /** The display app closed it. */
    const val END_CLOSED = "closed"

    /** A newer open (open, openOnRequest or openSession) took the bridge. */
    const val END_SUPERSEDED = "superseded by a newer open"

    /** The device left USB. */
    const val END_DETACHED = "device detached"

    /** A streaming session's socket broke: the display app closed its end, or stopped reading. */
    const val END_STREAM_CLOSED = "the display app's end of the stream closed"

    /** A streaming session's read pump failed; the bridge's status line says how. */
    const val END_PUMP_FAILED = "read pump failed"

    /** The process that opened it died. */
    const val END_CLIENT_GONE = "the display app's process died"

    /** Every client unbound from the bridge. */
    const val END_UNBOUND = "the display app unbound"

    /** Carlink took the adapter back from the app it had handed it to (takeBack, or an open of Carlink's). */
    const val END_TAKEN_BACK = "Carlink took the adapter back"

    /** The owner removed the app's approval, or turned other apps' access off. */
    const val END_ACCESS_REVOKED = "the owner removed this app's access"

    /** The bridge's service was destroyed. */
    const val END_BRIDGE_STOPPED = "bridge stopped"

    /**
     * From [SESSION_API_VERSION] the bridge caps every transfer's timeout here, and treats 0 or less (USB's
     * "wait forever") as this. The stick's longest is 10 s and the adapter's 2 s, so neither changes.
     */
    const val MAX_TRANSFER_TIMEOUT_MS = 30_000

    /** Oldest bridge API the display app can drive. */
    const val MIN_API_VERSION = 3

    /** First bridge API with ICarlinkBridge.requestPermission ([205]). */
    const val PERMISSION_PROMPT_API_VERSION = 2

    // ICarlinkBridge.requestPermission results.
    const val PERMISSION_GRANTED = 0
    const val PERMISSION_PENDING = 1
    const val PERMISSION_DENIED = 2
    const val PERMISSION_NO_DEVICE = 3

    const val BRIDGE_PACKAGE = "android.car.usb.handler"
    const val BRIDGE_SERVICE_CLASS = "com.enigy.carlink2.bridge.BridgeService"

    /** The package the bridge serves, and the only one unless the owner approves others (API 6). Play guarantees nobody else can publish it. */
    const val DISPLAY_PACKAGE = "com.enigy.carlink3"

    const val CLUSTER_ICON_AUTHORITY =
        "com.google.android.apps.automotive.templates.host.ClusterIconContentProvider"

    /** Bytes the bridge reads from the IN endpoint per bulkTransfer and writes to the socket as one chunk. */
    const val READ_CHUNK_BYTES = 16 * 1024

    /**
     * Largest single write() accepted. Binder transactions share a 1 MB buffer per process.
     * The OCBM client writes ≤16 KiB chunks; this is headroom, not a target.
     */
    const val MAX_WRITE_BYTES = 512 * 1024

    /** Largest single read() of an on-request session; the same Binder headroom as [MAX_WRITE_BYTES]. */
    const val MAX_READ_BYTES = 512 * 1024
}
