package com.enigy.carlink2.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.RemoteException
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * API 7: the bridge updating itself, when Carlink asks (ICarlinkBridge.installUpdate) after the owner tapped Update in
 * Settings, parked. Carlink names the bridge it pins; the bridge downloads it into its cache ([UpdateDownload]), checks
 * the size, the SHA-256, the package name and a version code above its own ([UpdateCheck]), and installs it with
 * PackageInstaller as an update of itself. Android also refuses an update not signed with the installed bridge's key.
 *
 * One update at a time: another call gets [BridgeContract.UPDATE_BUSY], unless the running one has waited longer than
 * [CONFIRM_WAIT_MS] for the owner's confirm, which is then dropped. The file goes when the update ends, either way, and
 * once the install session has its own copy. A success may never be told: the install ends this process, and Carlink
 * sees the binder die and checks the installed version.
 */
internal class BridgeUpdater(
    private val context: Context,
    private val parts: UpdateParts,
) {
    /** One update: who to tell, what it installs, and its session once committed. */
    private class Run(
        val listener: IBridgeUpdateListener,
    ) {
        @Volatile var request: UpdateRequest? = null

        @Volatile var sessionId: Int? = null

        // When the system asked for the owner's confirm; null until it does.
        @Volatile var waitingSince: Long? = null
    }

    private val lock = Any()
    private var current: Run? = null

    @Volatile private var last = "none"

    private val file get() = File(context.cacheDir, FILE_NAME)

    private val installed: Long get() = runCatching { parts.installedVersionCode() }.getOrDefault(0L)

    init {
        active = this
    }

    fun canInstall(): Boolean = parts.canInstall()

    /** ICarlinkBridge.installUpdate: returns at once; [listener] hears the rest. */
    fun start(
        json: String?,
        listener: IBridgeUpdateListener?,
    ) {
        if (listener == null) {
            updateLog("an update with no listener: nothing to tell, so not started")
            return
        }
        val run = claim(listener) ?: return
        parts.worker(Runnable { work(run, json) })
    }

    private fun claim(listener: IBridgeUpdateListener): Run? {
        val now = parts.now()
        val (run, dropped) =
            synchronized(lock) {
                val running = current
                val unanswered = running?.waitingSince?.let { now - it >= CONFIRM_WAIT_MS } == true
                if (running == null || unanswered) Run(listener).also { current = it } to running else null to null
            }
        if (dropped != null) {
            dropped.sessionId?.let(parts.installer::abandon)
            updateLog("the update waiting for the owner's confirm was dropped for a new one")
            tell(dropped.listener) { it.onResult(false, "The confirm went unanswered, and a new update replaced it") }
        }
        if (run == null) {
            updateLog("another update while one runs: busy")
            tell(listener) { it.onResult(false, BridgeContract.UPDATE_BUSY) }
        }
        return run
    }

    private fun work(
        run: Run,
        json: String?,
    ) {
        val request =
            when (val parsed = UpdateRequest.parse(json, parts.prefixes, installed)) {
                is UpdateRequest.Parsed.Refused -> return finish(run, false, parsed.reason)
                is UpdateRequest.Parsed.Ok -> parsed.request
            }
        run.request = request
        updateLog("bridge ${request.version} (version code ${request.versionCode}) from ${request.url}")
        tell(run.listener) { it.onState(BridgeContract.UPDATE_DOWNLOADING, request.url) }
        val refusal =
            when (val got = UpdateDownload(parts.net, parts.prefixes).fetch(request, file)) {
                is UpdateDownload.Got.Failed -> got.reason
                is UpdateDownload.Got.File -> {
                    tell(run.listener) { it.onState(BridgeContract.UPDATE_CHECKING, "${got.bytes} bytes, sha256 ${got.sha256}") }
                    UpdateCheck.refusal(request, got, runCatching { parts.archive(file) }.getOrNull(), context.packageName, installed)
                }
            }
        if (refusal != null) return finish(run, false, refusal)
        install(run, request)
    }

    private fun install(
        run: Run,
        request: UpdateRequest,
    ) {
        tell(run.listener) { it.onState(BridgeContract.UPDATE_INSTALLING, "bridge ${request.version}") }
        val id =
            try {
                parts.installer.install(file)
            } catch (e: IOException) {
                return finish(run, false, "The install couldn't start: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                // The session holds its own copy once written.
                file.delete()
            }
        run.sessionId = id
        updateLog("install session $id committed")
    }

    /** The install session's status ([UpdateStatusReceiver]). */
    fun onStatus(status: Intent) {
        val run = synchronized(lock) { current }
        val id = status.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (run == null || (run.sessionId != null && run.sessionId != id)) {
            updateLog("a status for session $id, which isn't the running update's")
            return
        }
        val version = run.request?.version ?: "?"
        when (val code = status.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> askOwner(run, status)
            PackageInstaller.STATUS_SUCCESS -> finish(run, true, "Bridge $version installed")
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish(run, false, "The update to bridge $version was cancelled")
            else -> finish(run, false, "The install failed: ${status.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $code"}")
        }
    }

    private fun askOwner(
        run: Run,
        status: Intent,
    ) {
        val confirm = UpdateStatusReceiver.confirmOf(status) ?: return finish(run, false, "The system asked for a confirm but sent no screen for it")
        run.waitingSince = parts.now()
        updateLog("the system asks the owner to confirm: handing Carlink the screen")
        tell(run.listener) { it.onUserAction(confirm) }
    }

    private fun finish(
        run: Run,
        ok: Boolean,
        message: String,
    ) {
        synchronized(lock) { if (current === run) current = null }
        file.delete()
        last = "${if (ok) "ok" else "failed"}: $message"
        if (ok) updateLog(message) else Log.w(TAG, "[BRIDGE_UPDATE] $message")
        BridgeStats.event("update ${if (ok) "ok" else "failed"}")
        tell(run.listener) { it.onResult(ok, message) }
    }

    private fun tell(
        listener: IBridgeUpdateListener,
        call: (IBridgeUpdateListener) -> Unit,
    ) {
        try {
            call(listener)
        } catch (e: RemoteException) {
            Log.w(TAG, "[BRIDGE_UPDATE] Carlink didn't hear it: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** For the status line: idle or running, and how the last update ended. */
    fun describe(): String {
        val run = synchronized(lock) { current }
        val now =
            when {
                run == null -> "idle"
                run.waitingSince != null -> "waiting for the owner's confirm"
                run.sessionId != null -> "installing"
                else -> "downloading"
            }
        return "update=$now(last=$last)"
    }

    companion object {
        private const val TAG = "Carlink2Bridge"
        private const val FILE_NAME = "bridge-update.apk"

        /** A confirm unanswered this long no longer blocks a new update. */
        const val CONFIRM_WAIT_MS = 10 * 60_000L

        /** The service's updater, for [UpdateStatusReceiver]; the latest one created. */
        @Volatile var active: BridgeUpdater? = null
            private set
    }
}

/** The checks a downloaded update must pass before it is installed: the pinned size and SHA-256, and it is a newer bridge. */
internal object UpdateCheck {
    /** Why [got] mustn't be installed, or null when it may. */
    fun refusal(
        request: UpdateRequest,
        got: UpdateDownload.Got.File,
        archive: UpdateParts.Archive?,
        ownPackage: String,
        installedVersionCode: Long,
    ): String? =
        when {
            got.bytes != request.size -> "The download is ${got.bytes} bytes; the pin says ${request.size}"
            got.sha256 != request.sha256 -> "The download's SHA-256 (${got.sha256}) isn't the pinned one (${request.sha256})"
            archive == null -> "The download isn't an APK the system can read"
            archive.packageName != ownPackage -> "The download is ${archive.packageName}, not the bridge"
            archive.versionCode != request.versionCode -> "The download is version code ${archive.versionCode}; the pin says ${request.versionCode}"
            archive.versionCode <= installedVersionCode -> "The download (version code ${archive.versionCode}) isn't newer than the installed bridge ($installedVersionCode)"
            else -> null
        }
}

/** The parts a new [BridgeUpdater] gets; tests replace it before the service starts, as they replace [PackageFacts.signer]. */
internal object BridgeUpdates {
    @Volatile var parts: (Context) -> UpdateParts = UpdateParts::real
}

private fun updateLog(message: String) {
    Log.i("Carlink2Bridge", "[BRIDGE_UPDATE] $message")
}
