package com.carlink.usb

import android.content.Context
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.enigy.carlink2.bridge.IBridgeAccessListener
import com.enigy.carlink2.bridge.ICarlinkBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Other apps' access as Carlink sees it ([BridgeAccess]), process-wide like [CarlinkBridge]: read from the bridge on
 * bind, when Settings opens, before each connect, and whenever the bridge says it changed (its access listener).
 * [onHandoverEnded] is how CarlinkManager learns the adapter came back, however the handover ended.
 */
object BridgeAccessHub {
    private val mutableState = MutableStateFlow(BridgeAccess())
    val state: StateFlow<BridgeAccess> = mutableState

    /** Runs (on a background thread) when a handover the hub saw ends: Carlink connects again, as after a detach. */
    @Volatile var onHandoverEnded: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The bridge Binder the listener is set on; a new bridge process needs it set again.
    @Volatile private var listening: android.os.IBinder? = null

    private val listener =
        object : IBridgeAccessListener.Stub() {
            override fun onAccessChanged() {
                scope.launch { CarlinkBridge.boundOrNull()?.let(::read) }
            }
        }

    /** The bridge (re)connected: read it again, and listen to the new process. */
    fun onBridgeConnected(bridge: ICarlinkBridge) {
        scope.launch { read(bridge) }
    }

    /** Read [bridge]'s access and publish it; Binder calls, so never on the main thread. */
    fun read(bridge: ICarlinkBridge): BridgeAccess {
        val api = runCatching { bridge.apiVersion }.getOrDefault(-1)
        val version = BridgeAccess.versionFromStatus(runCatching { bridge.status }.getOrNull())
        val next =
            if (api >= BridgeAccess.ACCESS_API_VERSION) {
                listen(bridge)
                BridgeAccess.parse(runCatching { bridge.access }.getOrNull(), api, version)
            } else {
                BridgeAccess(api, version)
            }
        publish(next)
        return next
    }

    /** Bind the bridge if need be and [read] it. */
    suspend fun refresh(context: Context): BridgeAccess =
        withContext(Dispatchers.IO) {
            CarlinkBridge.connect(context)?.let(::read) ?: BridgeAccess().also(::publish)
        }

    private fun listen(bridge: ICarlinkBridge) {
        val binder = bridge.asBinder()
        if (listening === binder) return
        runCatching { bridge.setAccessListener(listener) }
            .onSuccess { listening = binder }
            .onFailure { logWarn("[BRIDGE_ACCESS] couldn't listen to the bridge: ${it.message}", tag = Logger.Tags.USB) }
    }

    private fun publish(next: BridgeAccess) {
        val before = mutableState.value
        mutableState.value = next
        if (before.handedTo != null && next.handedTo == null) {
            logInfo("[BRIDGE_ACCESS] the handover to ${before.handedTo.packageName} ended", tag = Logger.Tags.USB)
            onHandoverEnded?.invoke()
        }
    }

    /**
     * One of Settings' actions on the bridge ([what] for the log): null when it went through, else the bridge's
     * refusal in its words. Reads the bridge again either way.
     */
    suspend fun act(
        context: Context,
        what: String,
        call: (ICarlinkBridge) -> Unit,
    ): String? =
        withContext(Dispatchers.IO) {
            logInfo("[UI_ACTION] Bridge access: $what", tag = "UI")
            val bridge = CarlinkBridge.connect(context) ?: return@withContext "The USB bridge isn't responding"
            // SecurityException, IllegalStateException and IllegalArgumentException carry the bridge's reason.
            val refusal =
                runCatching { call(bridge) }.exceptionOrNull()?.let { e ->
                    if (e is android.os.RemoteException) "The USB bridge didn't answer (${e.javaClass.simpleName})" else e.message ?: e.javaClass.simpleName
                }
            if (refusal != null) logWarn("[BRIDGE_ACCESS] $what refused: $refusal", tag = Logger.Tags.USB)
            read(bridge)
            refusal
        }

    suspend fun setAllowed(
        context: Context,
        on: Boolean,
    ) = act(context, if (on) "let other apps use the adapter" else "turn off other apps' access") { it.setOtherAppsAllowed(on) }

    suspend fun approve(
        context: Context,
        request: BridgeAccess.AccessRequest,
    ) = act(context, "approve ${request.packageName} (${request.sha256})") { it.approve(request.packageName, request.sha256) }

    suspend fun revoke(
        context: Context,
        packageName: String,
    ) = act(context, "remove $packageName") { it.revoke(packageName) }

    suspend fun dismiss(
        context: Context,
        packageName: String,
    ) = act(context, "dismiss $packageName's request") { it.dismissRequest(packageName) }

    suspend fun handOver(
        context: Context,
        packageName: String,
    ) = act(context, "hand the adapter to $packageName") { it.handOver(packageName) }

    suspend fun takeBack(context: Context) = act(context, "take the adapter back") { it.takeBack() }
}

/**
 * The order Carlink gives the adapter away and takes it back in, apart from CarlinkManager so it is testable: hand
 * over stops Carlink's session first (the bridge refuses while one is open), and a refusal reconnects; take back
 * asks the bridge, then connects as usual.
 *
 * [connect] only starts the connect, on CarlinkManager's own scope, and returns at once: hand over and take back
 * answer with the bridge's word without waiting for it. The callers run in UI scopes (a panel, Settings), and a
 * start cancelled there would strand CONNECTING with no cleanup, so later starts all skip.
 */
class AdapterHandover(
    private val stopSession: suspend () -> Unit,
    private val bridgeHandOver: suspend (String) -> String?,
    private val bridgeTakeBack: suspend () -> String?,
    private val connect: () -> Unit,
) {
    /** Null when [packageName] has the adapter now; otherwise the refusal, and Carlink connects again. */
    suspend fun handOver(packageName: String): String? {
        stopSession()
        val refusal = bridgeHandOver(packageName) ?: return null
        connect()
        return refusal
    }

    /** Null when the adapter is Carlink's again, and Carlink connects; otherwise the refusal. */
    suspend fun takeBack(): String? {
        val refusal = bridgeTakeBack()
        if (refusal == null) connect()
        return refusal
    }

    companion object {
        /** The connecting screen's words when the adapter is handed to another app, so Carlink opens nothing; null when it may connect. */
        fun connectBlockedBy(access: BridgeAccess): String? = access.handedToLabel?.let(BridgeAccessText::usingAdapter)
    }
}
