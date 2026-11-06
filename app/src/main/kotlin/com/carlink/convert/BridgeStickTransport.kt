package com.carlink.convert

import com.carlink.ocbm.BridgeSession
import com.enigy.carlink2.bridge.BridgeContract
import com.enigy.carlink2.bridge.ICarlinkBridge
import com.enigy.carlink2.bridge.IUsbBridgeSession

/**
 * A USB stick spoken to one transfer at a time: an IN transfer only when asked, of the length asked.
 * USB mass storage's Bulk-Only Transport needs that: a stick expects an IN only after a command block,
 * for the length that block gave, then exactly 13 bytes for its status.
 */
interface StickTransport {
    /** One bulk OUT transfer of all of [data]; false when it failed (a stall, a timeout, or the stick gone). */
    fun write(
        data: ByteArray,
        timeoutMs: Int,
    ): Boolean

    /** Bulk IN until [length] bytes, a short packet, or a failure: what came, maybe fewer; null when nothing did. */
    fun read(
        length: Int,
        timeoutMs: Int,
    ): ByteArray?

    /** CLEAR_FEATURE(ENDPOINT_HALT) on the bulk IN or OUT endpoint. */
    fun clearHalt(inEndpoint: Boolean): Boolean

    /** Reset recovery: Bulk-Only Mass Storage Reset, then CLEAR_FEATURE(ENDPOINT_HALT) on both bulk endpoints. */
    fun resetBulkOnly(): Boolean

    /** What the far end recorded of each transfer since the last call (debug builds), one per line; "" when nothing. */
    fun takeTrace(): String

    /** Let go of the stick. */
    fun close()
}

/**
 * The conversion's stick through the bridge's on-request session (bridge API 4,
 * [BridgeContract.ON_REQUEST_API_VERSION]): no read pump; each read is one call for an exact length.
 * Like [com.carlink.ocbm.BridgeBulkTransport] it uses and closes the bridge's one session only while it
 * is the session's owner ([BridgeSession]); from bridge API 5 through the session's own handle
 * ([session]), which the bridge refuses too once superseded.
 */
class BridgeStickTransport private constructor(
    private val bridge: ICarlinkBridge,
    // API 5: this open's own session; null on an older bridge, whose calls act on whichever session is open.
    private val session: IUsbBridgeSession?,
    val deviceName: String,
) : StickTransport {
    override fun write(
        data: ByteArray,
        timeoutMs: Int,
    ): Boolean = mine { (session?.write(data, timeoutMs) ?: bridge.write(data, timeoutMs)) == data.size } ?: false

    override fun read(
        length: Int,
        timeoutMs: Int,
    ): ByteArray? = mine { if (session != null) session.read(length, timeoutMs) else bridge.read(length, timeoutMs) }

    override fun clearHalt(inEndpoint: Boolean): Boolean = mine { (session?.clearHalt(inEndpoint) ?: bridge.clearHalt(inEndpoint)) >= 0 } ?: false

    override fun resetBulkOnly(): Boolean = mine { session?.resetBulkOnly() ?: bridge.resetBulkOnly() } ?: false

    override fun takeTrace(): String {
        val own = session ?: return mine { bridge.takeTrace() }.orEmpty()
        // API 5: the session's own trace, kept after it ended, so a stick pulled mid-write still shows its last transfers.
        return runCatching { own.takeTrace() }.getOrNull().orEmpty()
    }

    override fun close() {
        synchronized(BridgeSession.lock) {
            val owner = BridgeSession.owner === this
            if (owner) BridgeSession.owner = null
            // Its own session (API 5) is always its own to close; an older bridge's one session only while it owns it.
            val own = session
            if (own != null) {
                runCatching { own.close() }
            } else if (owner) {
                runCatching { bridge.close() }
            }
        }
    }

    /** [call] on the bridge while its session is still this stick's; null when it isn't, or the bridge went away. */
    private inline fun <T> mine(call: () -> T): T? =
        synchronized(BridgeSession.lock) {
            if (BridgeSession.owner === this) runCatching(call).getOrNull() else null
        }

    companion object {
        /** Open [deviceName] on request through [bridge] (API 4; API 5 with a handle), superseding any session; null when the bridge would not. */
        fun open(
            bridge: ICarlinkBridge,
            deviceName: String,
            trace: Boolean,
        ): BridgeStickTransport? =
            synchronized(BridgeSession.lock) {
                val t =
                    runCatching {
                        if (bridge.apiVersion >= BridgeContract.SESSION_API_VERSION) {
                            bridge.openSession(deviceName, true, trace, BridgeSession.client)?.let { BridgeStickTransport(bridge, it, deviceName) }
                        } else if (bridge.openOnRequest(deviceName, trace)) {
                            BridgeStickTransport(bridge, null, deviceName)
                        } else {
                            null
                        }
                    }.getOrNull()
                t?.also { BridgeSession.owner = it }
            }
    }
}
