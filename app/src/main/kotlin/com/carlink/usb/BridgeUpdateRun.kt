package com.carlink.usb

import android.content.Intent
import com.enigy.carlink2.bridge.BridgeContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One bridge update, after the owner confirmed it in Settings, parked: ask the bridge ([Port.bridge]), check it may
 * install apps, hand it the [pin] (ICarlinkBridge.installUpdate), and follow its steps. The system's confirm screen,
 * when it wants one, comes from the bridge and Carlink starts it ([Port.startConfirm]): the bridge has no activity.
 *
 * The install replaces the bridge and ends its process, so its success may never be told: the bridge's binder dies,
 * and the run binds again and checks the installed version against the pin ([afterRestart]). A bridge that died some
 * other way comes back on its old version, and the run says so. A confirm screen left unanswered (the owner went
 * elsewhere, or a drive covered it) ends the run after [Timing.confirmWaitMs], so Update can be tapped again. Each step
 * goes to [publish]; [run] returns the last.
 */
internal class BridgeUpdateRun(
    private val port: Port,
    private val pin: PinnedBridge,
    private val publish: (BridgeUpdateStep) -> Unit,
    private val timing: Timing = Timing(),
) {
    /** What the run needs of Carlink and the bridge. */
    interface Port {
        /** The bridge, bound (binding it if need be); null when it doesn't answer. */
        suspend fun bridge(): Bridge?

        /** The installed bridge's version code; null when it isn't installed. */
        fun installedVersionCode(): Long?

        /** Start the system's confirm screen; false when it couldn't be started. */
        fun startConfirm(confirm: Intent): Boolean

        fun log(message: String)
    }

    /** The bridge's update calls (API 7), and its death. */
    interface Bridge {
        fun canInstallPackages(): Boolean

        fun installUpdate(
            json: String,
            events: Events,
        )

        /** Call [block] when the bridge's process dies; false when it has already died. */
        fun onDeath(block: () -> Unit): Boolean
    }

    /** IBridgeUpdateListener, as the run hears it. */
    interface Events {
        fun onState(
            state: String,
            detail: String?,
        )

        fun onUserAction(confirm: Intent)

        fun onResult(
            ok: Boolean,
            message: String,
        )
    }

    data class Timing(
        /** How long the new bridge may take to answer after the old one's process ended. */
        val rebindMs: Long = 60_000,
        val pollMs: Long = 1_000,
        /** Once the bridge answers on its old version, how long the run still waits for the new one. */
        val oldVersionGraceMs: Long = 5_000,
        /**
         * How long the system's confirm may go unanswered before the run ends: a little over the bridge's own wait
         * ([BridgeContract.UPDATE_CONFIRM_WAIT_MS]), so the bridge has dropped the waiting update by the next tap.
         */
        val confirmWaitMs: Long = BridgeContract.UPDATE_CONFIRM_WAIT_MS + 5_000,
    )

    /** How the bridge's part ended: an outcome it told, or its process died. */
    private sealed interface End {
        data class Told(
            val ok: Boolean,
            val message: String,
        ) : End

        data object Died : End

        /** The confirm screen went unanswered for [Timing.confirmWaitMs]. */
        data object Unanswered : End
    }

    suspend fun run(): BridgeUpdateStep {
        publish(BridgeUpdateStep.Asking)
        val bridge = port.bridge() ?: return done(BridgeUpdateStep.Failed("The USB bridge isn't responding"))
        refusal(bridge)?.let { return done(it) }
        val end = CompletableDeferred<End>()
        val confirming = CompletableDeferred<Unit>()
        port.log("asking bridge to update to ${pin.versionName} (${pin.versionCode}) from ${pin.url}")
        val started = bridge.onDeath { end.complete(End.Died) } && runCatching { bridge.installUpdate(pin.json(), events(end, confirming)) }.isSuccess
        if (!started) return done(BridgeUpdateStep.Failed("The USB bridge didn't take the update"))
        val ended =
            coroutineScope {
                // Counted from the confirm screen, not the tap: downloading and checking take as long as they take.
                val unanswered =
                    launch {
                        confirming.await()
                        delay(timing.confirmWaitMs)
                        end.complete(End.Unanswered)
                    }
                end.await().also { unanswered.cancel() }
            }
        return when (ended) {
            End.Died -> afterRestart()
            End.Unanswered -> done(BridgeUpdateStep.Failed(UNANSWERED))
            is End.Told -> if (ended.ok) afterRestart() else done(BridgeUpdateStep.Failed(ended.message))
        }
    }

    /** Why [bridge] can't take the update now: it may not install apps, or it didn't answer; null when it can. */
    private fun refusal(bridge: Bridge): BridgeUpdateStep? =
        when (runCatching { bridge.canInstallPackages() }.getOrNull()) {
            true -> null
            false -> BridgeUpdateStep.NotAllowed
            null -> BridgeUpdateStep.Failed("The USB bridge didn't answer")
        }

    private fun events(
        end: CompletableDeferred<End>,
        confirming: CompletableDeferred<Unit>,
    ): Events =
        object : Events {
            override fun onState(
                state: String,
                detail: String?,
            ) {
                port.log("bridge: $state${detail?.let { " ($it)" }.orEmpty()}")
                publish(BridgeUpdateStep.ofState(state))
            }

            override fun onUserAction(confirm: Intent) {
                publish(BridgeUpdateStep.Confirming)
                port.log("the system asks the owner to confirm: starting its screen")
                if (port.startConfirm(confirm)) {
                    confirming.complete(Unit)
                } else {
                    end.complete(End.Told(false, "Carlink couldn't open the head unit's confirm screen"))
                }
            }

            override fun onResult(
                ok: Boolean,
                message: String,
            ) {
                port.log("bridge says ${if (ok) "ok" else "no"}: $message")
                end.complete(End.Told(ok, message))
            }
        }

    /** The bridge's process ended: bind the new one and see which version answers. */
    private suspend fun afterRestart(): BridgeUpdateStep {
        publish(BridgeUpdateStep.Restarting)
        var waited = 0L
        var oldFor = 0L
        while (waited < timing.rebindMs && oldFor < timing.oldVersionGraceMs) {
            delay(timing.pollMs)
            waited += timing.pollMs
            val answering = port.bridge() != null
            val installed = port.installedVersionCode()
            if (answering && installed != null && installed >= pin.versionCode) return done(BridgeUpdateStep.Updated(pin.versionName))
            if (answering) oldFor += timing.pollMs
        }
        val installed = port.installedVersionCode()
        return done(BridgeUpdateStep.Failed("The bridge came back on version code ${installed ?: "none"}, not ${pin.versionCode} (${pin.versionName})"))
    }

    private fun done(step: BridgeUpdateStep): BridgeUpdateStep {
        port.log("ended: $step")
        publish(step)
        return step
    }

    private companion object {
        const val UNANSWERED = "The head unit's confirm screen went unanswered. Tap Update to try again."
    }
}
