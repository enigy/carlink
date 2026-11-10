package com.enigy.carlink2.bridge

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * What the bridge's update ([BridgeUpdater]) needs from outside itself: the network, the package installer, the
 * downloaded file's package facts, the installed bridge's version code, whether the owner lets the bridge install
 * apps, the links it may fetch from, a thread to work on, and a clock. Tests replace them ([BridgeUpdates.parts]).
 */
internal class UpdateParts(
    val net: Net,
    val installer: Installer,
    val archive: (File) -> Archive?,
    val installedVersionCode: () -> Long,
    val canInstall: () -> Boolean,
    val prefixes: List<String>,
    val worker: (Runnable) -> Unit,
    val now: () -> Long,
) {
    /** One GET, redirects NOT followed, so the updater can check each hop's host. */
    fun interface Net {
        @Throws(IOException::class)
        fun get(url: URL): Response
    }

    /** A response: its [code], its `Location` for a redirect, and the [body] of a 200. [close] lets the connection go. */
    class Response(
        val code: Int,
        val location: String?,
        val body: InputStream?,
        private val onClose: () -> Unit = {},
    ) : Closeable {
        override fun close() {
            runCatching { body?.close() }
            onClose()
        }
    }

    /** Installs a checked APK as an update of this package; the session's status comes to [BridgeUpdater.onStatus]. */
    interface Installer {
        /**
         * The session's id, once the APK is written and the session committed. [onSession] hears the id first, before
         * the commit, so a status that comes at once already has its update to go to.
         */
        @Throws(IOException::class)
        fun install(
            file: File,
            onSession: (Int) -> Unit,
        ): Int

        fun abandon(sessionId: Int)
    }

    /** What a downloaded APK says it is. */
    data class Archive(
        val packageName: String,
        val versionCode: Long,
    )

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** The bridge's own parts: HTTPS, PackageInstaller, PackageManager. */
        fun real(context: Context): UpdateParts {
            val pm = context.packageManager
            val executor = Executors.newSingleThreadExecutor { Thread(it, "bridge-update").apply { isDaemon = true } }
            return UpdateParts(
                net = ::httpsGet,
                installer = SessionInstaller(context, BuildConfig.BENCH_REQUIRE_USER_ACTION),
                archive = { file -> pm.getPackageArchiveInfo(file.path, 0)?.let { Archive(it.packageName, it.longVersionCode) } },
                installedVersionCode = { pm.getPackageInfo(context.packageName, 0).longVersionCode },
                canInstall = { pm.canRequestPackageInstalls() },
                prefixes = listOf(BridgeContract.UPDATE_URL_PREFIX) + listOfNotNull(BuildConfig.BENCH_UPDATE_PREFIX.ifBlank { null }),
                worker = executor::execute,
                now = System::currentTimeMillis,
            )
        }

        /**
         * The session's user-action setting on [sdk]: none before Android 12, where the system always asks; required
         * when a debug bridge is built to force the confirm (-Pcarlink3.benchRequireUserAction=true), so the bench can
         * test Carlink starting it; otherwise not required, and the system decides.
         */
        @SuppressLint("InlinedApi")
        fun userAction(
            sdk: Int,
            benchRequire: Boolean,
        ): Int? =
            when {
                sdk < Build.VERSION_CODES.S -> null
                benchRequire -> PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                else -> PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
            }

        /** HTTPS only (a plain http URL is refused before this), no redirects followed, no cookies, a generic agent. */
        private fun httpsGet(url: URL): Response {
            val c = url.openConnection() as? HttpsURLConnection ?: throw IOException("not an HTTPS link: $url")
            c.instanceFollowRedirects = false
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.useCaches = false
            c.setRequestProperty("User-Agent", "CarlinkUsbBridge")
            val code = c.responseCode
            return Response(code, c.getHeaderField("Location"), if (code == HttpsURLConnection.HTTP_OK) c.inputStream else null, c::disconnect)
        }
    }
}

/**
 * The installer: a PackageInstaller session for this package, asking for no user action on API 31 and later (the
 * system decides; the bridge still handles a prompt) unless [requireUserAction], committed with a PendingIntent to
 * [UpdateStatusReceiver].
 */
private class SessionInstaller(
    private val context: Context,
    private val requireUserAction: Boolean,
) : UpdateParts.Installer {
    private val installer get() = context.packageManager.packageInstaller

    override fun install(
        file: File,
        onSession: (Int) -> Unit,
    ): Int {
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    UpdateParts.userAction(Build.VERSION.SDK_INT, requireUserAction)?.let(::setRequireUserAction)
                }
            }
        val id = installer.createSession(params)
        try {
            onSession(id)
            installer.openSession(id).use { session ->
                session.openWrite("bridge.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(statusSender(id))
            }
        } catch (e: IOException) {
            abandon(id)
            throw e
        } catch (e: SecurityException) {
            abandon(id)
            throw IOException("the system refused the install session: ${e.message}", e)
        }
        return id
    }

    override fun abandon(sessionId: Int) {
        runCatching { installer.abandonSession(sessionId) }
    }

    // FLAG_MUTABLE: the system adds the status extras. Below API 31 every PendingIntent is mutable and the flag is ignored.
    @SuppressLint("InlinedApi")
    private fun statusSender(sessionId: Int): IntentSender {
        val intent = Intent(context, UpdateStatusReceiver::class.java).setAction(UpdateStatusReceiver.ACTION)
        return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
    }
}
