package com.carlink.ocbm

import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

/**
 * The adapter's one-file install for the session in use ([OneFileInstall]). After each SUBSCRIBE it
 * reads the install state (MGMT_INFO) and the installed version (CH_FILE); it sends the way back
 * ([goBackToStock]); and it finishes the install only on the driver's Yes ([confirmFinish]): to Remove stock
 * CarPlay in Settings (Scott, 2 Nov), or because an update needs the room (Scott, 1 Nov). No screen confirms
 * the trial by being shown: until the driver says Yes, the trial keeps stock as its fallback.
 *
 * [confirmAllowed] is the build's word on sending MGMT_INSTALL_CONFIRM at all ([mayConfirmInstall]).
 */
class InstallSession(
    private val client: () -> OcbmClient?,
    private val scope: CoroutineScope,
    private val confirmAllowed: Boolean,
) {
    private val installState = MutableStateFlow<OneFileInstall?>(null)

    /** The install on the adapter in use; null with no session, or on an adapter that is not a one-file install. */
    val install: StateFlow<OneFileInstall?> = installState.asStateFlow()

    private val goBackState = MutableStateFlow<GoBackResult?>(null)

    /** The last answer to [goBackToStock]; kept across the adapter's restart, cleared by the next session. */
    val goBack: StateFlow<GoBackResult?> = goBackState.asStateFlow()

    /** A session is up (after CT_SUBSCRIBE): read the install, if the adapter has one. */
    fun onSubscribed() {
        goBackState.value = null
        scope.launch(Dispatchers.IO) { refresh() }
    }

    /** Read the install again, in the session in use: an update changed its version. */
    fun refreshNow() {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    /** The session ended: nothing is known about the next adapter yet. */
    fun reset() {
        installState.value = null
    }

    /** A stock adapter is on USB: a go-back in flight has landed (or this is another adapter), so stop saying it is restarting. */
    fun onStockAdapter() {
        installState.value = null
        goBackState.value = null
    }

    /**
     * Send MGMT_INSTALL_CONFIRM because the driver agreed to finish the install: "Remove stock CarPlay" in Settings
     * ([StockRemover]), or the room for an update ([AdapterUpdateSession.finish]). Then read the install again: the
     * ACK's status, or null with no answer. Blocks: call off the main thread. The one place the confirm is sent, and
     * only when [mayConfirmInstall] allows; else 1, unsent.
     */
    fun confirmFinish(): Int? {
        if (!mayConfirmInstall(installState.value?.mode, buildAllows = confirmAllowed)) {
            logWarn("[INSTALL] finish not sent: this build doesn't send the confirm, or the install isn't a trial", tag = Logger.Tags.ADAPTR)
            return 1
        }
        val status = client()?.mgmtAction(Ocbm.MGMT_INSTALL_CONFIRM)
        logWarn("[INSTALL] MGMT_INSTALL_CONFIRM (the driver agreed) status=${status ?: "no answer"}", tag = Logger.Tags.ADAPTR)
        if (status == 0) refresh()
        return status
    }

    /** Send MGMT_GO_STOCK; the answer lands in [goBack]. */
    fun goBackToStock() {
        logWarn("[INSTALL] Go back to stock CarPlay confirmed in the app", tag = Logger.Tags.ADAPTR)
        scope.launch(Dispatchers.IO) {
            val status = client()?.mgmtAction(Ocbm.MGMT_GO_STOCK)
            logWarn("[INSTALL] MGMT_GO_STOCK status=${status ?: "no answer"}", tag = Logger.Tags.ADAPTR)
            goBackState.value =
                when (status) {
                    0 -> GoBackResult.GOING
                    null -> GoBackResult.NO_ANSWER
                    else -> GoBackResult.REFUSED
                }
        }
    }

    private fun refresh() {
        val c = client() ?: return
        if (!c.supportsInstall) {
            installState.value = null
            return
        }
        val info = c.mgmtGetInfo()?.let { parse(it) } ?: return
        val mode = InstallMode.of(info.optString("install"))
        if (mode == null) {
            installState.value = null
            return
        }
        val version =
            c
                .pullFile(INSTALLED_VERSION, timeoutMs = VERSION_TIMEOUT_MS)
                .getOrNull()
                ?.toString(Charsets.UTF_8)
                ?.let(OneFileInstall::versionOf)
        // A trial's self-test, read now, while nothing streams: a pull during CarPlay timed out on the 2 Nov bench,
        // so Remove stock CarPlay said the self-test hadn't passed when it had.
        val selfTest =
            if (mode != InstallMode.TRIAL) {
                null
            } else {
                c
                    .pullFile(OneFileInstall.SELFTEST_FILE, timeoutMs = VERSION_TIMEOUT_MS)
                    .getOrNull()
                    ?.toString(Charsets.UTF_8)
                    ?.let(OneFileInstall::selfTestOf)
            }
        val installId =
            c
                .pullFile(OneFileInstall.INSTALL_ID_FILE, timeoutMs = VERSION_TIMEOUT_MS)
                .getOrNull()
                ?.toString(Charsets.UTF_8)
                ?.let(OneFileInstall::installIdOf)
        installState.value = OneFileInstall(mode, version, info.optString("serial"), selfTest, installId)
        logInfo(
            "[INSTALL] one-file install: ${mode.wire}, version ${version ?: "unknown"}" + (selfTest?.let { ", self-test $it" } ?: ""),
            tag = Logger.Tags.ADAPTR,
        )
    }

    private fun parse(json: String): JSONObject? =
        try {
            JSONObject(json)
        } catch (e: JSONException) {
            logWarn("[INSTALL] MGMT_INFO unreadable: ${e.message}", tag = Logger.Tags.ADAPTR)
            null
        }

    private companion object {
        const val INSTALLED_VERSION = "/etc/ocbm/installed_version"
        const val VERSION_TIMEOUT_MS = 5_000L
    }
}
