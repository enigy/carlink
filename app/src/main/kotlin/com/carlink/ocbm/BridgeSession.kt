package com.carlink.ocbm

/**
 * The bridge relays ONE session: its open() and openOnRequest() supersede the session before, and its
 * write(), read() and close() act on whichever session is open, whoever calls them. So only the
 * transport that opened it last ([owner]) may use or close it, and opens, transfers and closes take
 * [lock], so none of them lands between another's check and its call. One that was superseded must not
 * reach a session that now belongs to another device: on the bench (29 Oct) the stock adapter's link,
 * ending because the conversion's USB stick took the bridge, closed the stick's session 1 ms after it
 * opened.
 */
internal object BridgeSession {
    val lock = Any()

    @Volatile var owner: Any? = null
}
