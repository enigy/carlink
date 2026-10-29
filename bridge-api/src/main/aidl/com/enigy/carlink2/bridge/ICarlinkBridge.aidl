package com.enigy.carlink2.bridge;

import android.os.ParcelFileDescriptor;

/**
 * Display app (com.enigy.carlink3) -> USB bridge (android.car.usb.handler).
 *
 * The bridge owns the CPC200 USB device. Inbound adapter traffic is not delivered over
 * Binder: open() returns the read end of a socket that carries the adapter's bulk stream RAW,
 * in arrival order, chunked as read from the IN endpoint (API 3+; see
 * BridgeContract.RAW_STREAM_API_VERSION). The display app does its own framing. Outbound
 * traffic goes through write(), one bulk OUT transfer per call.
 *
 * Methods other than getApiVersion() throw SecurityException for callers that are not the
 * display app. Add new methods at the END only — transaction codes follow declaration order,
 * and the two apps are updated independently.
 */
interface ICarlinkBridge {
    /** BridgeContract.API_VERSION of the installed bridge build. */
    int getApiVersion();

    /** True when the bridge's UID holds USB permission for deviceName (a /dev/bus/usb path). */
    boolean hasPermission(String deviceName);

    /**
     * Open deviceName, claim its bulk interface and start forwarding inbound bytes.
     * Returns the read end of the message socket, or null on failure (reason in getStatus()).
     * A session that is already open is closed first. EOF on the socket means the session ended.
     */
    ParcelFileDescriptor open(String deviceName);

    /** One bulk OUT transfer of data. Returns bytes written, or -1 like bulkTransfer(). */
    int write(in byte[] data, int timeoutMs);

    /** Close the current session, if any. */
    void close();

    /** One-line diagnostics: handler config, user, USB permission, session and icon counters. */
    String getStatus();

    /**
     * API 2. For an adapter the platform never granted (it attached during boot, before the
     * driver's profile was up): show the system USB permission prompt for deviceName, ONCE per
     * device instance. Repeat calls only report the state, and a denial sticks until the
     * adapter re-enumerates (new deviceName). Returns a BridgeContract.PERMISSION_* value.
     */
    int requestPermission(String deviceName);

    /**
     * API 4. Open deviceName for transfers on request, as USB mass storage needs (a stick a stock
     * adapter shares): claim its bulk interface with force, and start NO read pump. Nothing is read
     * from the device until read() asks. A session that is already open is closed first. write()
     * and close() work on it as on an open() session. When trace is true the bridge records each
     * transfer's result for takeTrace(). Returns false on failure (reason in getStatus()).
     */
    boolean openOnRequest(String deviceName, boolean trace);

    /**
     * API 4. On an openOnRequest() session: bulk IN transfers until length bytes are in, a short
     * packet ends the transfer, or a transfer fails. Returns what came, maybe fewer than length,
     * or null when the first transfer failed (a stall, or timeoutMs passed).
     */
    byte[] read(int length, int timeoutMs);

    /**
     * API 4. On an openOnRequest() session: CLEAR_FEATURE(ENDPOINT_HALT) on the bulk IN endpoint
     * (inEndpoint) or the bulk OUT one. Returns controlTransfer()'s result: 0, or -1 on failure.
     */
    int clearHalt(boolean inEndpoint);

    /**
     * API 4. On an openOnRequest() session: USB mass storage's reset recovery. Bulk-Only Mass
     * Storage Reset (class request 0xFF to the interface), then CLEAR_FEATURE(ENDPOINT_HALT) on
     * both bulk endpoints. True when all three went through.
     */
    boolean resetBulkOnly();

    /** API 4. The transfer results recorded since the last call (openOnRequest with trace), one per line; "" when none. */
    String takeTrace();
}
