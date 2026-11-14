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

    /** The update ([to], when the adapter said) failed its trial, and the adapter put [version] back. */
    data class Kept(
        val version: String,
        val reason: String,
        val to: String? = null,
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

    /** The last line of the adapter's start-up self-test (`/tmp/ocbm_selftest.txt`); null when it couldn't be read. */
    fun selfTest(): String?
}

/** What the update needs of the app around it. */
interface UpdateHost {
    /** The adapter in use; null with no session. */
    fun box(): UpdateBox?

    /** Why an update can't start now (the car moving, a call), or null when it can. */
    fun notNow(): String?

    /** The update was kept: read the install again, for its new version. */
    fun updated()

    /** Whether the adapter in use has carried a CarPlay session in its trial. */
    fun carriedCarPlay(): Boolean

    /** Send MGMT_INSTALL_CONFIRM, the driver having agreed to finish the install: the ACK's status, or null with no answer. */
    fun confirmInstall(): Int?
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
    private val install: StateFlow<OneFileInstall?>,
    private val scope: CoroutineScope,
    private val switches: Switches,
    private val timing: Timing = Timing(),
) {
    /** The build's word on updates. */
    data class Switches(
        /** BuildConfig.ADAPTER_UPDATES: on in debug builds, off in release builds until a package is published. */
        val enabled: Boolean,
        /** BuildConfig.INSTALL_CONFIRM: whether a trial adapter without the room may finish its install to make it. */
        val canFinish: Boolean,
    )

    /** How long each wait lasts. */
    data class Timing(
        /** Between reads of MGMT_INFO while the adapter installs and checks the update. */
        val pollMs: Long = 2_000,
        /** From the send to "the update didn't happen": the plan's 5 minutes, as for a stick install. */
        val deadlineMs: Long = 300_000,
        /** MGMT_UPDATE's answer: the adapter hashes the package (busybox sha256sum) first. */
        val startMs: Long = 30_000,
        /** A final install read short of room at the session's start: between reads of its free flash ([recheckRoom]). */
        val roomRecheckMs: Long = 20_000,
        /** And for how long, from the session's start. */
        val roomRecheckForMs: Long = 180_000,
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
            if (switches.enabled) updateOffer(i, a.takesUpdates, a.pkg, a.freeKb, switches.canFinish) else UpdateOffer.None
        }.stateIn(scope, SharingStarted.Eagerly, UpdateOffer.None)

    private val phaseState = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)

    /** Where the update stands; kept across the adapter's restart, back to Idle on [dismiss]. */
    val phase: StateFlow<UpdatePhase> = phaseState.asStateFlow()

    private val finishAskState = MutableStateFlow<UpdateOffer.FinishFirst?>(null)

    /** The offer whose install the driver is asked to finish ([askToFinish]); null when nothing is asked. */
    val finishAsk: StateFlow<UpdateOffer.FinishFirst?> = finishAskState.asStateFlow()

    private val lock = Any()

    // The update's own work: getting, sending and starting the package, then polling for the outcome.
    private var job: Job? = null
    private var deadline: Job? = null

    // The session start's re-reads of the free flash ([recheckRoom]).
    @Volatile private var roomCheck: Job? = null

    /** A session is up (after CT_SUBSCRIBE): read the adapter, and the outcome of an update in flight. */
    fun onSubscribed() {
        if (!switches.enabled) return
        scope.launch(Dispatchers.IO) {
            val b = host.box() ?: return@launch
            val pkg = packages.known()
            val info = b.info()?.let(::parse)
            adapter.value = Adapter(b.takesUpdates, freeKb(info), pkg)
            logInfo(
                "[UPDATE] adapter takes updates=${b.takesUpdates}, ${adapter.value.freeKb ?: "?"} KB free; " +
                    "package ${pkg?.let { "${it.version} (from ${it.fromMin}, ~${it.needKb} KB)" } ?: "none"}",
                tag = Logger.Tags.ADAPTR,
            )
            if (info != null) settle(info)
            if (shortOfRoom()) recheckRoom(b)
        }
    }

    /** MGMT_INFO's `rootfs_free_kb`; null when it didn't say. */
    private fun freeKb(info: JSONObject?): Int? = info?.optInt("rootfs_free_kb", -1)?.takeIf { it >= 0 }

    /** Less flash free than the package needs, by the last read, on a final install (or one not read yet). */
    private fun shortOfRoom(): Boolean {
        val a = adapter.value
        val need = a.pkg?.needKb ?: return false
        val free = a.freeKb ?: return false
        val mode = install.value?.mode
        return free < need && (mode == null || mode == InstallMode.FINAL)
    }

    /**
     * The first connect after a finish can read the free flash while the finish is still freeing it (9 Nov bench:
     * 2728 KB, then 4128 KB two minutes later), and the update showed as not fitting until the next connect. So a final
     * install short of room reads MGMT_INFO again every [Timing.roomRecheckMs], for [Timing.roomRecheckForMs] or until
     * the package fits.
     */
    private fun recheckRoom(b: UpdateBox) {
        roomCheck?.cancel()
        roomCheck =
            scope.launch(Dispatchers.IO) {
                repeat((timing.roomRecheckForMs / timing.roomRecheckMs).toInt()) {
                    delay(timing.roomRecheckMs)
                    if (!shortOfRoom()) return@launch
                    val free = freeKb(b.info()?.let(::parse)) ?: return@repeat
                    adapter.value = adapter.value.copy(freeKb = free)
                    if (!shortOfRoom()) {
                        logInfo("[UPDATE] $free KB free on a second look: the package fits", tag = Logger.Tags.ADAPTR)
                        return@launch
                    }
                }
                logInfo("[UPDATE] still ${adapter.value.freeKb} KB free ${timing.roomRecheckForMs / MS_PER_S} s into the session", tag = Logger.Tags.ADAPTR)
            }
    }

    /** The session ended: an adapter installing or checking the update is restarting; nothing is known of the next one yet. */
    fun reset() {
        roomCheck?.cancel()
        adapter.value = Adapter()
        finishAskState.value = null
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
                phaseState.value = UpdatePhase.Failed(NOT_STARTED, it)
                return
            }
            logWarn("[UPDATE] update to ${pkg.version} confirmed in the app", tag = Logger.Tags.ADAPTR)
            phaseState.value = UpdatePhase.Downloading(pkg.version, 0, 0)
            job?.cancel()
            job = scope.launch(Dispatchers.IO) { run(pkg.version) }
        }
    }

    /**
     * Update on a [UpdateOffer.FinishFirst] offer (Scott, 1 Nov): when the trial has proven itself ([finishBlocked]: a
     * passed self-test and a CarPlay session), ask the driver ([finishAsk]); otherwise refuse, with the reason. Nothing is
     * written to the adapter either way.
     */
    fun askToFinish() {
        val offer = offer.value as? UpdateOffer.FinishFirst ?: return
        synchronized(lock) {
            if (phaseState.value is UpdatePhase.Updating) return
            host.notNow()?.let {
                phaseState.value = UpdatePhase.Failed(NOT_STARTED, it)
                return
            }
        }
        scope.launch(Dispatchers.IO) {
            val blocked = finishBlocked(host.box()?.selfTest(), host.carriedCarPlay())
            if (blocked == null) {
                finishAskState.value = offer
                return@launch
            }
            logInfo("[UPDATE] ${offer.pkg.version} needs the install finished, and it can't be now: $blocked", tag = Logger.Tags.ADAPTR)
            outcome(UpdatePhase.Failed(NOT_INSTALLED, "${offer.why} $blocked"))
        }
    }

    /**
     * The driver agreed to finish the install: send the install confirm. The adapter finishes when this drive ends, and the
     * update is offered in place on the next one.
     */
    fun finish() {
        val offer = finishAskState.value ?: return
        finishAskState.value = null
        logWarn("[UPDATE] finishing the install for ${offer.pkg.version}, agreed in the app", tag = Logger.Tags.ADAPTR)
        scope.launch(Dispatchers.IO) {
            val status = host.confirmInstall()
            logWarn("[UPDATE] MGMT_INSTALL_CONFIRM status=${status ?: "no answer"}", tag = Logger.Tags.ADAPTR)
            if (status == 0) return@launch
            val body = if (status == null) "The adapter didn't answer." else "The adapter refused it."
            outcome(UpdatePhase.Failed("The install didn't finish", "$body Nothing was changed."))
        }
    }

    /** The driver declined to finish the install: the update isn't installed, and the row says why. */
    fun declineFinish() {
        val offer = finishAskState.value ?: return
        finishAskState.value = null
        outcome(UpdatePhase.Failed(NOT_INSTALLED, "${offer.why} It goes in once the install has finished."))
    }

    /** Show [result] on the row, unless an update is in flight. */
    private fun outcome(result: UpdatePhase.Failed) {
        synchronized(lock) {
            if (phaseState.value !is UpdatePhase.Updating) phaseState.value = result
        }
    }

    /** OK on an outcome: back to the offer. */
    fun dismiss() {
        synchronized(lock) {
            if (phaseState.value !is UpdatePhase.Updating) phaseState.value = UpdatePhase.Idle
        }
    }

    private suspend fun run(to: String) {
        install(fetch(to) ?: return, to)
    }

    /**
     * Bench only (debug builds, the Mac's bench hooks of 13 Nov): install the package in [bytes] whatever the offer says,
     * reported as any update is. A bench package keeps its VERSION with new code, which the adapter takes only while its
     * own bench flag is set (/tmp/bench_update_any_version, ocbm_update.sh).
     */
    fun benchUpdate(bytes: ByteArray) {
        val pkg =
            UpdatePackageFile.read(bytes) ?: run {
                logWarn("[bench] the bench file is not an update package: nothing sent", tag = Logger.Tags.ADAPTR)
                return
            }
        synchronized(lock) {
            if (phaseState.value is UpdatePhase.Updating) return
            logWarn("[bench] update to ${pkg.version} from a bench file", tag = Logger.Tags.ADAPTR)
            phaseState.value = UpdatePhase.Downloading(pkg.version, 0, 0)
            job?.cancel()
            job = scope.launch(Dispatchers.IO) { install(bytes, pkg.version) }
        }
    }

    /** Send [bytes], start it, and poll for the outcome. */
    private suspend fun install(
        bytes: ByteArray,
        to: String,
    ) {
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
            UpdateState.ROLLED_BACK -> UpdatePhase.Kept(result?.kept ?: "the version it had", result?.reason.orEmpty(), result?.version)
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
        const val NOT_STARTED = "The update didn't start"
        const val NOT_INSTALLED = "The update wasn't installed"
    }
}
