package com.enigy.carlink2.bridge;

import android.os.ParcelFileDescriptor;

/** One USB session. Its handle continues to report the end reason after a handoff. */
interface IUsbBridgeSession {
    /** Take the raw IN socket once for a streaming session. Null for on-request or ended sessions. */
    ParcelFileDescriptor takeStream();
    int write(in byte[] data, int timeoutMs);
    byte[] read(int length, int timeoutMs);
    int clearHalt(boolean inEndpoint);
    boolean resetBulkOnly();
    String takeTrace();
    void close();
    /** Null while active. Otherwise one of BridgeContract.END_*. */
    @nullable String getEndReason();
}
