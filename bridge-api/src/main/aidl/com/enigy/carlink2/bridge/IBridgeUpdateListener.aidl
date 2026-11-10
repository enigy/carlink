package com.enigy.carlink2.bridge;

import android.content.Intent;

/**
 * API 7. The display app's, passed to ICarlinkBridge.installUpdate(): the update's steps and its outcome. The update
 * replaces the bridge and ends its process, so a success may never be told: the display app sees the binder die,
 * binds again and checks the installed version. Add new methods at the END only.
 */
oneway interface IBridgeUpdateListener {
    /** A step (BridgeContract.UPDATE_DOWNLOADING, UPDATE_CHECKING or UPDATE_INSTALLING), and a detail for the log. */
    void onState(String state, String detail);

    /** The system wants the owner to confirm the install: the display app starts confirm (the bridge has no activity). */
    void onUserAction(in Intent confirm);

    /** The outcome: ok, or why not in plain words. */
    void onResult(boolean ok, String message);
}
