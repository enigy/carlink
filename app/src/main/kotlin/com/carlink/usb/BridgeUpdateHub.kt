package com.carlink.usb

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.RemoteException
import android.provider.Settings
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.enigy.carlink2.bridge.BridgeContract
import com.enigy.carlink2.bridge.IBridgeUpdateListener
import com.enigy.carlink2.bridge.ICarlinkBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bridge's update as Settings sees it, process-wide like [BridgeAccessHub]: what the installed bridge is against
 * the pin ([offer], read when Settings opens) and where an update stands ([step]). [update] runs one
 * ([BridgeUpdateRun]); [onUpdated] is how CarlinkManager learns to connect the adapter again through the new bridge.
 * Settings only: never while driving, never on the connecting screen.
 */
object BridgeUpdateHub {
    private val mutableOffer = MutableStateFlow<BridgeUpdateOffer>(BridgeUpdateOffer.None)
    val offer: StateFlow<BridgeUpdateOffer> = mutableOffer

    private val mutableStep = MutableStateFlow<BridgeUpdateStep>(BridgeUpdateStep.Idle)
    val step: StateFlow<BridgeUpdateStep> = mutableStep

    /** Runs (on a background thread) when the bridge was updated: Carlink connects the adapter again. */
    @Volatile var onUpdated: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The installed bridge against this Carlink's pin; binds the bridge if need be, for its API. */
    suspend fun refresh(context: Context): BridgeUpdateOffer =
        withContext(Dispatchers.IO) {
            val api = CarlinkBridge.connect(context)?.let { runCatching { it.apiVersion }.getOrDefault(-1) } ?: -1
            BridgeUpdateOffer.of(BridgePackage.installedVersionCode(context), api, BridgePackage.pinned(context)).also { mutableOffer.value = it }
        }

    /** The owner confirmed the update to [pin]. Nothing when one is already under way. */
    fun update(
        context: Context,
        pin: PinnedBridge,
    ) {
        if (mutableStep.value.running) return
        val appContext = context.applicationContext
        logInfo("[UI_ACTION] Update USB bridge to ${pin.versionName}", tag = "UI")
        mutableStep.value = BridgeUpdateStep.Asking
        scope.launch {
            val end = BridgeUpdateRun(RealPort(appContext), pin, { mutableStep.value = it }).run()
            if (end is BridgeUpdateStep.Updated) onUpdated?.invoke()
            refresh(appContext)
        }
    }

    /** The head unit's "install unknown apps" page for the bridge, or null where there is none ([InstallAppsPage]). */
    fun allowInstallsIntent(context: Context): Intent? {
        val intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${BridgeContract.BRIDGE_PACKAGE}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent.takeIf { InstallAppsPage.isReal(it.resolveActivity(context.packageManager)?.packageName) }
    }

    /** Open [allowInstallsIntent]; false when it couldn't be opened. */
    fun openAllowInstalls(context: Context): Boolean {
        val intent = allowInstallsIntent(context) ?: return false
        return try {
            context.startActivity(intent)
            logInfo("[BRIDGE_UPDATE] opened the bridge's install-apps setting", tag = Logger.Tags.USB)
            true
        } catch (e: ActivityNotFoundException) {
            logWarn("[BRIDGE_UPDATE] the install-apps setting couldn't be opened: ${e.message}", tag = Logger.Tags.USB)
            false
        }
    }

    /** The run's view of Carlink and the bridge. */
    private class RealPort(
        private val context: Context,
    ) : BridgeUpdateRun.Port {
        override suspend fun bridge(): BridgeUpdateRun.Bridge? = CarlinkBridge.connect(context)?.let(::RealBridge)

        override fun installedVersionCode(): Long? = BridgePackage.installedVersionCode(context)

        override fun startConfirm(confirm: Intent): Boolean =
            try {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                log("the confirm screen couldn't be started: ${e.message}")
                false
            } catch (e: SecurityException) {
                log("the confirm screen refused Carlink: ${e.message}")
                false
            }

        override fun log(message: String) {
            logInfo("[BRIDGE_UPDATE] $message", tag = Logger.Tags.USB)
        }
    }

    private class RealBridge(
        private val bridge: ICarlinkBridge,
    ) : BridgeUpdateRun.Bridge {
        override fun canInstallPackages(): Boolean = bridge.canInstallPackages()

        override fun installUpdate(
            json: String,
            events: BridgeUpdateRun.Events,
        ) {
            bridge.installUpdate(
                json,
                object : IBridgeUpdateListener.Stub() {
                    override fun onState(
                        state: String,
                        detail: String?,
                    ) = events.onState(state, detail)

                    override fun onUserAction(confirm: Intent?) {
                        if (confirm != null) events.onUserAction(confirm) else events.onResult(false, "The bridge sent no confirm screen")
                    }

                    override fun onResult(
                        ok: Boolean,
                        message: String?,
                    ) = events.onResult(ok, message.orEmpty())
                },
            )
        }

        override fun onDeath(block: () -> Unit): Boolean =
            try {
                bridge.asBinder().linkToDeath({ block() }, 0)
                true
            } catch (e: RemoteException) {
                logWarn("[BRIDGE_UPDATE] the bridge had already died: ${e.message}", tag = Logger.Tags.USB)
                false
            }
    }
}

/** Who answers the bridge's "install unknown apps" page, and whether that can really let it install apps. */
internal object InstallAppsPage {
    /**
     * Android Automotive's placeholder for settings a car doesn't offer: it answers with "not supported on this device".
     * Stock AAOS 13 sends the page here (the Mac's emulator, 10 Nov), so an Allow that opened it would be a dead end.
     */
    const val AAOS_STUBS = "com.android.car.frameworkpackagestubs"

    /** Something answers, and it isn't the placeholder. */
    fun isReal(packageName: String?): Boolean = packageName != null && packageName != AAOS_STUBS
}
