package com.carlink.ocbm

import android.os.Binder
import android.os.IBinder

/**
 * The bridge relays ONE session: its open() and openOnRequest() supersede the session before, and its
 * write(), read() and close() act on whichever session is open, whoever calls them. So only the
 * transport that opened it last ([owner]) may use or close it, and opens, transfers and closes take
 * [lock], so none of them lands between another's check and its call. One that was superseded must not
 * reach a session that now belongs to another device: on the bench (29 Oct) the stock adapter's link,
 * ending because the conversion's USB stick took the bridge, closed the stick's session 1 ms after it
 * opened.
 *
 * From bridge API 5 (BridgeContract.SESSION_API_VERSION) each open also has a handle of its own
 * (IUsbBridgeSession), and the bridge itself refuses a superseded handle's calls and says why a session
 * ended. The owner rule stays: it is what keeps an older bridge's one session safe, and costs nothing on
 * a newer one.
 */
internal object BridgeSession {
    val lock = Any()

    @Volatile var owner: Any? = null

    /**
     * This process's Binder, handed to the bridge with every API 5 open: the bridge watches it and ends the
     * session if this process dies. One for the process is enough, and kept here so it lives as long.
     */
    val client: IBinder = Binder()
}
