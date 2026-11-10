package com.enigy.carlink2.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** API 7: the bridge updating itself to the version Carlink pins, and every way it refuses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], shadows = [ShadowSigningPackageManager::class])
class BridgeUpdateTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val apk = ByteArray(4096) { (it * 31).toByte() }
    private val fetched = mutableListOf<String>()
    private val responses = mutableMapOf<String, () -> UpdateParts.Response>()
    private val installs = mutableListOf<ByteArray>()
    private val abandoned = mutableListOf<Int>()
    private var archive: UpdateParts.Archive? = UpdateParts.Archive(context.packageName, NEW_CODE)
    private var allowed = true
    private val defaultParts = BridgeUpdates.parts

    private val parts =
        UpdateParts(
            net = { url ->
                fetched += url.toString()
                responses[url.toString()]?.invoke() ?: UpdateParts.Response(404, null, null)
            },
            installer =
                object : UpdateParts.Installer {
                    override fun install(file: File): Int {
                        installs += file.readBytes()
                        return SESSION
                    }

                    override fun abandon(sessionId: Int) {
                        abandoned += sessionId
                    }
                },
            archive = { archive },
            installedVersionCode = { INSTALLED_CODE },
            canInstall = { allowed },
            prefixes = listOf(BridgeContract.UPDATE_URL_PREFIX),
            worker = { it.run() },
            now = { clock },
        )
    private var clock = 1_000L
    private val updater = BridgeUpdater(context, parts)
    private val file get() = File(context.cacheDir, "bridge-update.apk")

    @After
    fun tearDown() {
        BridgeUpdates.parts = defaultParts
        ShadowBinder.reset()
        ShadowSigningPackageManager.signers.clear()
    }

    @Test
    fun `a pinned, newer bridge is downloaded through GitHub's asset host, checked and installed, and the file goes`() {
        serveThroughRedirect()
        val heard = Heard()
        updater.start(request(), heard)
        assertEquals(listOf(PINNED_URL, ASSET_URL), fetched)
        assertEquals(listOf(BridgeContract.UPDATE_DOWNLOADING, BridgeContract.UPDATE_CHECKING, BridgeContract.UPDATE_INSTALLING), heard.states)
        assertTrue("the installer got the very bytes", apk.contentEquals(installs.single()))
        assertFalse("the session holds its own copy; the bridge's goes", file.exists())
        assertNull("nothing decided until the session says", heard.result)

        updater.onStatus(status(PackageInstaller.STATUS_SUCCESS))
        assertEquals(true to "Bridge 1.0.10 installed", heard.result)
    }

    @Test
    fun `a link that isn't a GitHub release download of enigy carlink is refused before anything is fetched`() {
        for (url in listOf("http://github.com/enigy/carlink/releases/download/v224/b.apk", "https://example.com/enigy/carlink/releases/download/v224/b.apk", "https://github.com/someone/carlink/releases/download/v224/b.apk")) {
            val heard = Heard()
            updater.start(request(url = url), heard)
            assertFalse(url, heard.result!!.first)
            assertTrue(heard.result!!.second, heard.result!!.second.contains("isn't a GitHub release download of enigy/carlink"))
        }
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `a redirect anywhere but GitHub's asset hosts, or to plain http, is refused`() {
        for (to in listOf("https://evil.example/b.apk", "http://release-assets.githubusercontent.com/b.apk")) {
            responses[PINNED_URL] = { UpdateParts.Response(302, to, null) }
            val heard = Heard()
            updater.start(request(), heard)
            assertTrue(heard.result!!.second, heard.result!!.second.contains("which the bridge doesn't fetch from"))
            assertTrue(installs.isEmpty())
        }
    }

    @Test
    fun `a wrong hash, a wrong package or another version is refused and the file deleted`() {
        serveThroughRedirect()
        val wrongHash = Heard()
        updater.start(request(sha256 = "00".repeat(32)), wrongHash)
        assertTrue(wrongHash.result!!.second, wrongHash.result!!.second.contains("isn't the pinned one"))
        assertFalse(file.exists())

        archive = UpdateParts.Archive("com.example.other", NEW_CODE)
        val wrongPackage = Heard()
        updater.start(request(), wrongPackage)
        assertTrue(wrongPackage.result!!.second, wrongPackage.result!!.second.contains("not the bridge"))
        assertFalse(file.exists())

        archive = UpdateParts.Archive(context.packageName, INSTALLED_CODE - 1)
        val older = Heard()
        updater.start(request(), older)
        assertTrue(older.result!!.second, older.result!!.second.contains("the pin says $NEW_CODE"))
        assertFalse(file.exists())
        assertTrue("nothing reached the installer", installs.isEmpty())
    }

    @Test
    fun `a pin that isn't newer than the installed bridge is refused before anything is fetched`() {
        val heard = Heard()
        updater.start(request(versionCode = INSTALLED_CODE), heard)
        assertTrue(heard.result!!.second, heard.result!!.second.contains("isn't newer than the installed one"))
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `a size other than the pinned one, or a download past twice it, is refused`() {
        serveThroughRedirect()
        val small = Heard()
        updater.start(request(size = apk.size + 1L), small)
        assertTrue(small.result!!.second, small.result!!.second.contains("the pin says ${apk.size + 1}"))
        val huge = Heard()
        updater.start(request(size = apk.size / 3L), huge)
        assertTrue(huge.result!!.second, huge.result!!.second.contains("past twice the pinned size"))
        assertFalse(file.exists())
    }

    @Test
    fun `another call while one runs is busy, unless the running one left the confirm unanswered`() {
        serveThroughRedirect()
        val first = Heard()
        updater.start(request(), first)
        val second = Heard()
        updater.start(request(), second)
        assertEquals(false to BridgeContract.UPDATE_BUSY, second.result)
        assertNull("the first carries on", first.result)

        updater.onStatus(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, Intent("confirm")))
        clock += BridgeUpdater.CONFIRM_WAIT_MS
        val third = Heard()
        updater.start(request(), third)
        assertFalse(first.result!!.first)
        assertEquals("the unanswered session is abandoned", listOf(SESSION), abandoned)
        assertEquals(BridgeContract.UPDATE_INSTALLING, third.states.last())
    }

    @Test
    fun `each session status reaches Carlink`() {
        serveThroughRedirect()
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        val asked = Heard()
        updater.start(request(), asked)
        updater.onStatus(status(PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirm))
        assertEquals("the system's own screen, for Carlink to start", confirm.action, asked.confirm!!.action)
        assertNull("still waiting for the owner", asked.result)
        updater.onStatus(status(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(false to "The update to bridge 1.0.10 was cancelled", asked.result)

        val failed = Heard()
        updater.start(request(), failed)
        updater.onStatus(status(PackageInstaller.STATUS_FAILURE_CONFLICT).putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertEquals(false to "The install failed: INSTALL_FAILED_UPDATE_INCOMPATIBLE", failed.result)

        val other = Heard()
        updater.start(request(), other)
        updater.onStatus(status(PackageInstaller.STATUS_SUCCESS, session = SESSION + 1))
        assertNull("another session's status is not this update's", other.result)
    }

    @Test
    fun `only Carlink may ask, and the bridge says whether it may install apps`() {
        BridgeUpdates.parts = { parts }
        val controller = Robolectric.buildService(BridgeService::class.java).create()
        try {
            val service = controller.get()
            shadowOf(service.packageManager).setPackagesForUid(CARLINK_UID, BridgeContract.DISPLAY_PACKAGE)
            shadowOf(service.packageManager).setPackagesForUid(OTHER_UID, "com.example.other")
            ShadowSigningPackageManager.signers[CARLINK_UID] = ShadowSigningPackageManager.sha256(BuildConfig.CALLER_CERTS.split(',').first())
            val bridge = ICarlinkBridge.Stub.asInterface(service.onBind(Intent()))
            ShadowBinder.setCallingUid(OTHER_UID)
            assertThrows(SecurityException::class.java) { bridge.installUpdate(request(), Heard()) }
            assertThrows(SecurityException::class.java) { bridge.canInstallPackages() }
            assertTrue(fetched.isEmpty())

            ShadowBinder.setCallingUid(CARLINK_UID)
            assertTrue(bridge.canInstallPackages())
            allowed = false
            assertFalse(bridge.canInstallPackages())
            assertTrue(bridge.status, bridge.status.contains("update=idle"))
        } finally {
            controller.destroy()
        }
    }

    private fun serveThroughRedirect() {
        responses[PINNED_URL] = { UpdateParts.Response(302, ASSET_URL, null) }
        responses[ASSET_URL] = { UpdateParts.Response(200, null, ByteArrayInputStream(apk)) }
    }

    private fun request(
        url: String = PINNED_URL,
        versionCode: Long = NEW_CODE,
        size: Long = apk.size.toLong(),
        sha256: String = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) },
    ): String =
        JSONObject()
            .put(BridgeContract.UPDATE_VERSION, "1.0.10")
            .put(BridgeContract.UPDATE_VERSION_CODE, versionCode)
            .put(BridgeContract.UPDATE_URL, url)
            .put(BridgeContract.UPDATE_SIZE, size)
            .put(BridgeContract.UPDATE_SHA256, sha256)
            .toString()

    private fun status(
        code: Int,
        session: Int = SESSION,
    ) = Intent(UpdateStatusReceiver.ACTION)
        .putExtra(PackageInstaller.EXTRA_SESSION_ID, session)
        .putExtra(PackageInstaller.EXTRA_STATUS, code)

    /** What Carlink's listener heard. */
    private class Heard : IBridgeUpdateListener.Stub() {
        val states = mutableListOf<String>()
        var confirm: Intent? = null
        var result: Pair<Boolean, String>? = null

        override fun onState(
            state: String,
            detail: String?,
        ) {
            states += state
        }

        override fun onUserAction(confirm: Intent?) {
            this.confirm = confirm
        }

        override fun onResult(
            ok: Boolean,
            message: String,
        ) {
            result = ok to message
        }
    }

    private companion object {
        const val PINNED_URL = "https://github.com/enigy/carlink/releases/download/v225/carlink_bridge-1.0.10.apk"
        const val ASSET_URL = "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc"
        const val INSTALLED_CODE = 10L
        const val NEW_CODE = 11L
        const val SESSION = 42
        const val CARLINK_UID = 10_123
        const val OTHER_UID = 10_999
    }
}
