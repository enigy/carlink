package com.enigy.carlink2.bridge;

import android.os.ParcelFileDescriptor;

/**
 * API 5. One session ICarlinkBridge.openSession() opened, for the display app that opened it.
 *
 * Every call acts on this session only. Once it has ended (closed, superseded by a newer open,
 * the device detached, the client's process died, the bridge stopped), the transfer calls fail
 * as they do on a closed session (-1, null or false) and never reach a later session, and
 * getEndReason() says why it ended. The calls throw SecurityException for callers that are not
 * the display app, like ICarlinkBridge's; from API 6, a handle an approved app opened is that
 * app's alone. Add new methods at the END only.
 */
interface IUsbBridgeSession {
    /**
     * The read end of a streaming session's socket, the one open() returns: the adapter's bulk
     * stream RAW, and EOF once the session ends. Handed out once; null after that, for a session
     * opened on request, and once the session has ended.
     */
    ParcelFileDescriptor takeStream();

    /** One bulk OUT transfer of data. Returns bytes written, or -1 like bulkTransfer(); -1 once the session has ended. */
    int write(in byte[] data, int timeoutMs);

    /** As ICarlinkBridge.read(), on a session opened on request; null on a streaming session and once the session has ended. */
    byte[] read(int length, int timeoutMs);

    /** As ICarlinkBridge.clearHalt(), on a session opened on request; -1 otherwise. */
    int clearHalt(boolean inEndpoint);

    /** As ICarlinkBridge.resetBulkOnly(), on a session opened on request; false otherwise. */
    boolean resetBulkOnly();

    /** As ICarlinkBridge.takeTrace(), this session's own, also after it ended; "" when none. */
    String takeTrace();

    /** End this session (BridgeContract.END_CLOSED). Does nothing once it has ended. */
    void close();

    /** Null while the session is open; afterwards why it ended, one of BridgeContract's END_ values. */
    String getEndReason();
}
