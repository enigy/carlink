package com.carlink.cluster

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import com.carlink.BuildConfig
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.util.SigningCerts

/**
 * Headless CarAppService for cluster navigation display only.
 *
 * Currently always returns [ClusterMainSession] (GM AAOS path — NavigationManager relay
 * feeding GM's internal OnStarTurnByTurnManager). For non-GM AAOS platforms,
 * [CarlinkClusterSession] (direct NavigationTemplate rendering) should be returned instead.
 * Deferred: gate session choice on PlatformInfo.isGmAaos. NOTE: 2026-04-20 POTATO GM runs
 * show the cluster is driven entirely by VMSClusterService/NavigationClusterService +
 * OnStarTurnByTurnManager — our CarAppService may not be bound on GM at all. Verify
 * NavigationManager.updateTrip() actually reaches GM's cluster before treating
 * ClusterMainSession as the authoritative "GM path".
 *
 * MainActivity remains the sole LAUNCHER and owns all USB/video/audio pipelines.
 * This service does NOT initialize CarlinkManager, video, audio, or USB.
 *
 * Ships android:enabled="true" in the manifest (cluster navigation is on by default);
 * [ClusterNavigation.applyComponentState] turns the component off when the user does.
 *
 * Lifecycle is logged so a drive's trail shows how far the host got: "Service created" means the
 * Templates Host bound us; "Creating session" means it accepted our AppInfo and handshake. Created
 * without a session points at the handshake (for example a missing app-level minCarApiLevel).
 */
class CarlinkClusterService : CarAppService() {
    override fun onCreate() {
        super.onCreate()
        logInfo("[CLUSTER_SVC] Service created — the Templates Host is binding", tag = Logger.Tags.CLUSTER)
    }

    override fun onDestroy() {
        logInfo("[CLUSTER_SVC] Service destroyed", tag = Logger.Tags.CLUSTER)
        super.onDestroy()
    }

    /**
     * Only the hosts in the library's allowlist may bind (security review M2), so another app can't
     * pose as a car host and read the route. Every truck log since v16 showed GM's Templates Host
     * passing it: the sample list carries its certificate (c241ffbc…). Debug builds still take any
     * host, for emulators whose host is signed differently.
     */
    override fun createHostValidator(): HostValidator = if (BuildConfig.DEBUG) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR else allowlist

    private val allowlist: HostValidator by lazy {
        HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    }

    private fun logHost() {
        val host = hostInfo
        if (host == null) {
            logWarn("[CLUSTER_SVC] host unknown (no HostInfo)", tag = Logger.Tags.CLUSTER)
            return
        }
        val allowed = runCatching { allowlist.isValidHost(host) }.getOrDefault(false)
        logWarn(
            "[CLUSTER_SVC] host ${host.packageName} uid=${host.uid} cert=${SigningCerts.sha256(packageManager, host.packageName)} " +
                "allowlist=${if (allowed) "pass" else "FAIL"} (${if (BuildConfig.DEBUG) "debug build: not enforced" else "enforced"})",
            tag = Logger.Tags.CLUSTER,
        )
    }

    // Deprecated pre-SessionInfo overload; kept for hosts that bind without SessionInfo.
    @Suppress("DEPRECATION")
    override fun onCreateSession(): Session {
        logInfo("[CLUSTER_SVC] Creating session (no SessionInfo — fallback)", tag = Logger.Tags.CLUSTER)
        logHost()
        return ClusterMainSession()
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        logInfo("[CLUSTER_SVC] Creating session: displayType=${sessionInfo.displayType}", tag = Logger.Tags.CLUSTER)
        logHost()
        return ClusterMainSession()
    }
}
