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
    const val API_VERSION = 3

    /**
     * Carlink3: from this API the bridge relays the adapter's bulk stream RAW — chunks as read from
     * the IN endpoint, no header parsing, no message boundaries. The display app's OCBM client does
     * its own framing (`Reassembler`). Older bridges (Carlink2, API ≤ 2) parsed the stock 0x55AA55AA
     * message framing and must not be used with an OCBM adapter.
     */
    const val RAW_STREAM_API_VERSION = 3

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

    /** The only package the bridge serves. Play guarantees nobody else can publish it. */
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
}
