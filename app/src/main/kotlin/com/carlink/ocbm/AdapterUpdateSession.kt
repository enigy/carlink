package com.carlink.ocbm

import com.carlink.convert.InstallerImage
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

/** Where an adapter update stands, for Settings and the connecting screen ([AdapterUpdateSession.phase]). */
sealed interface UpdatePhase {
    /** Past "Update", until the adapter is back with an outcome or the deadline passes. */
    sealed interface Updating : UpdatePhase {
        /** The version being installed. */
        val to: String
    }

    data object Idle : UpdatePhase

    /** Getting the package; [total] is 0 until its size is known. */
    data class Downloading(
        override val to: String,
        val done: Long,
        val total: Long,
    ) : Updating

    /** Sending it to the adapter over the OCBM link. */
    data class Sending(
        override val to: String,
        val done: Long,
        val total: Long,
    ) : Updating

    /** The adapter checks the package and places it, then restarts. */
    data class Installing(
        override val to: String,
    ) : Updating

    /** The adapter left USB: it restarts into the update's trial. */
    data class Restarting(
        override val to: String,
    ) : Updating

    /** Back, on the update: its start-up self-test decides whether it stays. */
    data class Checking(
        override val to: String,
    ) : Updating

    data class Updated(
        val version: String,
    ) : UpdatePhase

    /** The update failed its trial, and the adapter put [version] back. */
    data class Kept(
        val version: String,
        val reason: String,
    ) : UpdatePhase

    /** The adapter checked the package and refused it; nothing changed. */
    data class Refused(
        val reason: String,
    ) : UpdatePhase

    data class Failed(
        val title: String,
        val body: String,
    ) : UpdatePhase
}

/** What the update needs of the adapter in use. */
interface UpdateBox {
    /** The box takes MGMT_UPDATE (CAP_UPDATE). */
    val takesUpdates: Boolean

    /** MGMT_INFO's JSON; null with no answer. */
    fun info(): String?

    /** Send [bytes] to [path] over CH_FILE. */
    fun push(
        path: String,
        bytes: ByteArray,
        progress: (done: Long, total: Long) -> Unit,
    ): Result<Unit>

    /** MGMT_UPDATE with the package's SHA-256: the ACK's status, or null with no answer within [timeoutMs]. */
    fun update(
        sha256: String,
        timeoutMs: Long,
    ): Int?
}

/** What the update needs of the app around it. */
interface UpdateHost {
    /** The adapter in use; null with no session. */
    fun box(): UpdateBox?

    /** Why an update can't start now (the car moving, a call), or null when it can. */
    fun notNow(): String?

    /** The update was kept: read the install again, for its new version. */
    fun updated()
}

/**
 * Updating the adapter in use to the newest OCBM this build knows (remote updates plan, U2). After each SUBSCRIBE it
 * reads the adapter's free flash and update state (MGMT_INFO); [offer] combines them with the one-file [install] and
 * the package ([packages]) into what Settings offers. It never prompts: an update starts only on the driver's tap,
 * confirmed ([update]), and not while the [host] has a reason against it (the car moving, a call).
 *
 * The package is checked, sent to the adapter (CH_FILE) and started (MGMT_UPDATE). The adapter installs it as a
 * trial and restarts; this session lasts across that restart ([reset] keeps it), and each SUBSCRIBE and each poll
 * reads the outcome: kept ([UpdatePhase.Updated]), put back ([UpdatePhase.Kept]) or refused. An adapter not back with
 * an outcome within [Timing.deadlineMs] of the send is said to have not updated, and nothing is offered again on its own.
 */
@Suppress("TooManyFunctions") // a state machine: its entry points and the steps between its phases
class AdapterUpdateSession(
    private val host: UpdateHost,
    private val packages: UpdateSupplier,
    install: StateFlow<OneFileInstall?>,
    private val scope: CoroutineScope,
    private val switches: Switches,
    private val timing: Timing = Timing(),
) {
    /** The build's word on updates. */
    data class Switches(
        /** BuildConfig.ADAPTER_UPDATES: on in debug builds, off in release builds until a package is published. */
        val enabled: Boolean,
        /** BuildConfig.STOCK_CONVERSION: whether a trial adapter the package doesn't fit can take the round trip. */
        val canConvert: Boolean,
    )

    /** How long each wait lasts. */
    data class Timing(
        /** Between reads of MGMT_INFO while the adapter installs and checks the update. */
        val pollMs: Long = 2_000,
        /** From the send to "the update didn't happen": the plan's 5 minutes, as for a stick install. */
        val deadlineMs: Long = 300_000,
        /** MGMT_UPDATE's answer: the adapter hashes the package (busybox sha256sum) first. */
        val startMs: Long = 30_000,
    )

    /** What MGMT_INFO said of the adapter in use. */
    private data class Adapter(
        val takesUpdates: Boolean = false,
        val freeKb: Int? = null,
        val pkg: UpdatePackage? = null,
    )

    private val adapter = MutableStateFlow(Adapter())

    /** What Settings offers the adapter in use. */
    val offer: StateFlow<UpdateOffer> =
        combine(install, adapter) { i, a ->
            if (switches.enabled) updateOffer(i, a.takesUpdates, a.pkg, a.freeKb, switches.canConvert) else UpdateOffer.None
        }.stateIn(scope, SharingStarted.Eagerly, UpdateOffer.None)

    private val phaseState = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)

    /** Where the update stands; kept across the adapter's restart, back to Idle on [dismiss]. */
    val phase: StateFlow<UpdatePhase> = phaseState.asStateFlow()

    private val lock = Any()

    // The update's own work: getting, sending and starting the package, then polling for the outcome.
    private var job: Job? = null
    private var deadline: Job? = null

    /** A session is up (after CT_SUBSCRIBE): read the adapter, and the outcome of an update in flight. */
    fun onSubscribed() {
        if (!switches.enabled) return
        scope.launch(Dispatchers.IO) {
            val b = host.box() ?: return@launch
            val pkg = packages.known()
            val info = b.info()?.let(::parse)
            adapter.value = Adapter(b.takesUpdates, info?.optInt("rootfs_free_kb", -1)?.takeIf { it >= 0 }, pkg)
            logInfo(
                "[UPDATE] adapter takes updates=${b.takesUpdates}, ${adapter.value.freeKb ?: "?"} KB free; " +
                    "package ${pkg?.let { "${it.version} (from ${it.fromMin}, ~${it.needKb} KB)" } ?: "none"}",
                tag = Logger.Tags.ADAPTR,
            )
            if (info != null) settle(info)
        }
    }

    /** The session ended: an adapter installing or checking the update is restarting; nothing is known of the next one yet. */
    fun reset() {
        adapter.value = Adapter()
        synchronized(lock) {
            when (val now = phaseState.value) {
                is UpdatePhase.Installing -> phaseState.value = UpdatePhase.Restarting(now.to)
                is UpdatePhase.Checking -> phaseState.value = UpdatePhase.Restarting(now.to)
                else -> Unit
            }
        }
    }

    /** Why an update can't start now; null when it can. Asked before the confirm dialog, and again on the confirm. */
    fun notNowReason(): String? = host.notNow()

    /** The driver confirmed the update [offer] holds: get the package, send it, and start it. */
    fun update() {
        synchronized(lock) {
            val pkg = (offer.value as? UpdateOffer.InPlace)?.pkg ?: return
            if (phaseState.value is UpdatePhase.Updating) return
            host.notNow()?.let {
                phaseState.value = UpdatePhase.Failed("The update didn't start", it)
                return
            }
            logWarn("[UPDATE] update to ${pkg.version} confirmed in the app", tag = Logger.Tags.ADAPTR)
            phaseState.value = UpdatePhase.Downloading(pkg.version, 0, 0)
            job?.cancel()
            job = scope.launch(Dispatchers.IO) { run(pkg.version) }
        }
    }

    /** OK on an outcome: back to the offer. */
    fun dismiss() {
        synchronized(lock) {
            if (phaseState.value !is UpdatePhase.Updating) phaseState.value = UpdatePhase.Idle
        }
    }

    private suspend fun run(to: String) {
        val bytes = fetch(to) ?: return
        if (!sendAndStart(bytes, to) || !step(UpdatePhase.Installing(to))) return
        startDeadline(to)
        while (phaseState.value is UpdatePhase.Updating) {
            delay(timing.pollMs)
            val info = host.box()?.info()?.let(::parse)
            if (info != null) settle(info)
        }
    }

    /** The package for [to], checked: by its hash ([packages]), then by what it says it is. Null after a failure. */
    private fun fetch(to: String): ByteArray? {
        val bytes =
            packages.obtain { done, total -> step(UpdatePhase.Downloading(to, done, total)) }.getOrElse {
                fail("Couldn't get the update", it.message ?: "The download failed.")
                return null
            }
        val inside = UpdatePackageFile.read(bytes)
        if (inside == null || inside.version != to) {
            fail("Couldn't get the update", "The package isn't OCBM $to${inside?.let { " (it says ${it.version})" }.orEmpty()}. Nothing was sent.")
            return null
        }
        return bytes
    }

    /** Send [bytes] to the adapter and start the update: true once the adapter took it. */
    private fun sendAndStart(
        bytes: ByteArray,
        to: String,
    ): Boolean {
        step(UpdatePhase.Sending(to, 0, bytes.size.toLong()))
        val b = host.box()
        if (b == null) {
            fail("The adapter isn't connected", "Nothing was sent. Try again once CarPlay is back.")
            return false
        }
        b.push(AdapterUpdates.BOX_PATH, bytes) { done, total -> step(UpdatePhase.Sending(to, done, total)) }.onFailure {
            logWarn("[UPDATE] push failed: ${it.message}", tag = Logger.Tags.ADAPTR)
            fail("Couldn't send the update", "The adapter didn't get the package. Nothing was changed.")
            return false
        }
        val status = b.update(InstallerImage.sha256Hex(bytes), timing.startMs)
        logWarn("[UPDATE] MGMT_UPDATE status=${status ?: "no answer"}", tag = Logger.Tags.ADAPTR)
        when (status) {
            0 -> Unit
            null ->
                fail(
                    "The adapter didn't answer",
                    "It may still install the update. If CarPlay doesn't come back in a few minutes, unplug the adapter and plug it back in.",
                )
            else -> fail("The adapter didn't start the update", "It has no update script, or the package didn't arrive whole. Nothing was changed.")
        }
        return status == 0
    }

    /** Move to [next] while the update is still in flight; false once it is over (an outcome came, or the deadline). */
    private fun step(next: UpdatePhase.Updating): Boolean =
        synchronized(lock) {
            if (phaseState.value !is UpdatePhase.Updating) return false
            phaseState.value = next
            true
        }

    private fun fail(
        title: String,
        body: String,
    ) {
        synchronized(lock) {
            if (phaseState.value is UpdatePhase.Updating) phaseState.value = UpdatePhase.Failed(title, body)
        }
    }

    private fun startDeadline(to: String) {
        synchronized(lock) {
            deadline?.cancel()
            deadline =
                scope.launch {
                    delay(timing.deadlineMs)
                    logWarn("[UPDATE] no outcome ${timing.deadlineMs / MS_PER_S} s after the send", tag = Logger.Tags.ADAPTR)
                    fail(
                        "The update didn't happen",
                        "The adapter isn't back with OCBM $to. If CarPlay doesn't come back, unplug the adapter and plug it back in.",
                    )
                }
        }
    }

    /** What MGMT_INFO says of an update in flight, once the adapter has it: its progress, or its outcome. */
    private fun settle(info: JSONObject) {
        val state = UpdateState.of(info.optString("update"))
        val result = UpdateResult.parse(info.optString("update_result"))
        synchronized(lock) {
            val now = phaseState.value as? UpdatePhase.Updating ?: return
            // Until MGMT_UPDATE is answered, MGMT_INFO still speaks of the last update; ocbmd clears it on the start.
            if (now is UpdatePhase.Downloading || now is UpdatePhase.Sending) return
            val next = next(now.to, state, result) ?: return
            phaseState.value = next
            if (next is UpdatePhase.Updating) return
            deadline?.cancel()
            logWarn("[UPDATE] outcome: ${info.optString("update_result")}", tag = Logger.Tags.ADAPTR)
            if (next is UpdatePhase.Updated) host.updated()
        }
    }

    /** The phase MGMT_INFO's [state] and [result] stand for, in an update to [to]; null when they say nothing new. */
    private fun next(
        to: String,
        state: UpdateState,
        result: UpdateResult?,
    ): UpdatePhase? =
        when (state) {
            UpdateState.NONE -> null
            UpdateState.INSTALLING -> UpdatePhase.Installing(to)
            UpdateState.TRIAL -> UpdatePhase.Checking(to)
            UpdateState.COMMITTED -> UpdatePhase.Updated(result?.version ?: to)
            UpdateState.ROLLED_BACK -> UpdatePhase.Kept(result?.kept ?: "the version it had", result?.reason.orEmpty())
            UpdateState.REFUSED -> UpdatePhase.Refused(result?.reason?.ifEmpty { null } ?: "It gave no reason.")
        }

    private fun parse(json: String): JSONObject? =
        try {
            JSONObject(json)
        } catch (e: JSONException) {
            logWarn("[UPDATE] MGMT_INFO unreadable: ${e.message}", tag = Logger.Tags.ADAPTR)
            null
        }

    private companion object {
        const val MS_PER_S = 1000
    }
}
