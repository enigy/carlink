package com.enigy.carlink2.bridge;

import android.os.ParcelFileDescriptor;
import com.enigy.carlink2.bridge.IBridgeAccessListener;
import com.enigy.carlink2.bridge.IUsbBridgeSession;

/**
 * Display app (com.enigy.carlink3) -> USB bridge (android.car.usb.handler).
 *
 * The bridge owns the CPC200 USB device. Inbound adapter traffic is not delivered over
 * Binder: open() returns the read end of a socket that carries the adapter's bulk stream RAW,
 * in arrival order, chunked as read from the IN endpoint (API 3+; see
 * BridgeContract.RAW_STREAM_API_VERSION). The display app does its own framing. Outbound
 * traffic goes through write(), one bulk OUT transfer per call.
 *
 * One session is open at a time, and every open ends the one before it. write(), read(), close()
 * and the other transfer calls here act on whichever session is open. From API 5 openSession()
 * returns a handle of the session's own (IUsbBridgeSession), whose calls act on that session only.
 *
 * Methods other than getApiVersion() throw SecurityException for callers that are not the
 * display app, and so do a session handle's. From API 6 the owner may let apps it approves use
 * openSession(), getStatus(), hasPermission() and requestPermission() while Carlink hands the
 * adapter over (BridgeContract.ACCESS_API_VERSION); the access calls stay the display app's alone. Add new methods at the END only — transaction
 * codes follow declaration order, and the two apps are updated independently.
 */
interface ICarlinkBridge {
    /** BridgeContract.API_VERSION of the installed bridge build. */
    int getApiVersion();

    /** True when the bridge's UID holds USB permission for deviceName (a /dev/bus/usb path). */
    boolean hasPermission(String deviceName);

    /**
     * Open deviceName, claim its bulk interface and start forwarding inbound bytes.
     * Returns the read end of the message socket, or null on failure (reason in getStatus()).
     * A session that is already open is closed first; from API 5, an open that fails before it can
     * claim the device (not attached, no permission, no bulk interface) leaves it open. EOF on the
     * socket means the session ended.
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
     * from the device until read() asks. A session that is already open is closed first, as by
     * open(). write() and close() work on it as on an open() session. When trace is true the bridge records each
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

    /**
     * API 5. Open deviceName as open() does (onRequest false: the read pump, its socket taken with
     * the handle's takeStream()) or as openOnRequest() does (onRequest true; trace as there), and
     * return a handle of the session's own. The handle's calls act on this session only: once it
     * has ended they fail as on a closed session and never reach a later one, and getEndReason()
     * says why it ended. client is a Binder of the caller's: when its process dies, the session
     * ends. Ends the session before it like open(). Returns null on failure (reason in getStatus()).
     */
    IUsbBridgeSession openSession(String deviceName, boolean onRequest, boolean trace, IBinder client);

    /**
     * API 6. Other apps' access as JSON: {"v":1,"on","carlinkVerified","approved":[{"package","label",
     * "sha256","installer","approvedAt","lastUsedAt","sessions"}],"requests":[{"package","label","sha256",
     * "installer","firstAt","lastAt","count","keyChanged"}],"handedTo":{"package","since"} or null,
     * "holder": the package whose session is open, or null}. Times are epoch milliseconds.
     */
    String getAccess();

    /**
     * API 6. Let approved apps use the adapter (on), or not (off, the default). Off ends an app's
     * session (END_ACCESS_REVOKED) and forgets every approval and request.
     */
    void setOtherAppsAllowed(boolean on);

    /**
     * API 6. Approve packageName signed by sha256 (64 hex digits, colons optional), as a request
     * recorded it. Throws IllegalArgumentException when no such request is recorded, and
     * IllegalStateException while access is off. Replaces an approval of the package by another key.
     */
    void approve(String packageName, String sha256);

    /** API 6. Remove packageName's approval; ends its session and handover (END_ACCESS_REVOKED). */
    void revoke(String packageName);

    /** API 6. Forget packageName's requests. */
    void dismissRequest(String packageName);

    /**
     * API 6. Hand the adapter to packageName, an approved app, until takeBack(), any open of the
     * display app's, a revoke, access turned off, the app's session client dying, or
     * BridgeContract.HANDOVER_GRACE_MS without a session of the app's. Throws
     * IllegalStateException (why, in its message) while a session of the display app's is open, or
     * when the app isn't approved.
     */
    void handOver(String packageName);

    /** API 6. End the handover, and the app's session with it (END_TAKEN_BACK). Nothing when none. */
    void takeBack();

    /** API 6. Tell listener of every change to getAccess(); null forgets every listener. Dead ones are dropped. */
    void setAccessListener(IBridgeAccessListener listener);
}
