package com.carlink.ocbm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Updating the adapter in use: from the tap to each outcome the adapter can report, over a fake adapter. */
@RunWith(RobolectricTestRunner::class)
class AdapterUpdateSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val install = MutableStateFlow<OneFileInstall?>(OneFileInstall(InstallMode.TRIAL, "1.0.0"))
    private val pkg = UpdatePackage(version = "1.1.0", fromMin = "1.0.0", needKb = 387)

    @Volatile private var box: FakeBox? = FakeBox()

    @Volatile private var notNow: String? = null
    private val updated = AtomicInteger()
    private lateinit var session: AdapterUpdateSession

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** MGMT_INFO as ocbmd writes the fields the update reads (enigy/ocbm f8f18c8). */
    private fun info(
        update: String = "none",
        result: String = "",
        freeKb: Int = 1252,
    ) = """{"rootfs_free_kb":$freeKb,"install":"trial","update":"$update","update_result":"$result"}"""

    private class FakeBox : UpdateBox {
        @Volatile override var takesUpdates = true

        @Volatile var infoJson: String? = null
        val pushed = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        val started = CopyOnWriteArrayList<String>()

        @Volatile var pushResult: Result<Unit> = Result.success(Unit)

        @Volatile var ack: Int? = 0

        override fun info(): String? = infoJson

        override fun push(
            path: String,
            bytes: ByteArray,
            progress: (done: Long, total: Long) -> Unit,
        ): Result<Unit> {
            pushed.add(path to bytes)
            progress(bytes.size.toLong(), bytes.size.toLong())
            return pushResult
        }

        override fun update(
            sha256: String,
            timeoutMs: Long,
        ): Int? {
            started.add(sha256)
            return ack
        }
    }

    private class FakeSupplier(
        @Volatile var pkg: UpdatePackage?,
        @Volatile var bytes: Result<ByteArray> = Result.success(UpdateFixtures.PACKAGE_1_1_0),
    ) : UpdateSupplier {
        override fun known(): UpdatePackage? = pkg

        override fun obtain(progress: (done: Long, total: Long) -> Unit): Result<ByteArray> = bytes
    }

    private fun start(
        supplier: UpdateSupplier = FakeSupplier(pkg),
        enabled: Boolean = true,
        timing: AdapterUpdateSession.Timing = AdapterUpdateSession.Timing(pollMs = 20, deadlineMs = 20_000, startMs = 100),
    ): AdapterUpdateSession {
        box?.infoJson = box?.infoJson ?: info()
        session =
            AdapterUpdateSession(
                host =
                    object : UpdateHost {
                        override fun box(): UpdateBox? = box

                        override fun notNow(): String? = notNow

                        override fun updated() {
                            updated.incrementAndGet()
                        }
                    },
                packages = supplier,
                install = install,
                scope = scope,
                switches = AdapterUpdateSession.Switches(enabled = enabled, canConvert = true),
                timing = timing,
            )
        session.onSubscribed()
        return session
    }

    /** Start an update to [pkg] and wait until the adapter has it and is installing it. */
    private fun sendIt(): FakeBox {
        waitUntil(2000) { session.offer.value is UpdateOffer.InPlace }
        session.update()
        waitUntil(2000) { session.phase.value is UpdatePhase.Installing }
        return box!!
    }

    /** The adapter restarts into the update: its link ends, and a new one comes up saying [infoJson]. */
    private fun restart(infoJson: String) {
        box = null
        session.reset()
        assertEquals(UpdatePhase.Restarting("1.1.0"), session.phase.value)
        box = FakeBox().also { it.infoJson = infoJson }
        session.onSubscribed()
    }

    @Test
    fun `an update that passes its self-test goes from the tap to updated`() {
        start()
        val b = sendIt()
        assertEquals(AdapterUpdates.BOX_PATH, b.pushed.single().first)
        assertArrayEquals(UpdateFixtures.PACKAGE_1_1_0, b.pushed.single().second)
        assertEquals(UpdateFixtures.PACKAGE_1_1_0_SHA256, b.started.single())

        b.infoJson = info(update = "installing")
        Thread.sleep(100) // shows that "installing" changes nothing
        assertEquals(UpdatePhase.Installing("1.1.0"), session.phase.value)

        restart(info(update = "trial"))
        waitUntil(2000) { session.phase.value == UpdatePhase.Checking("1.1.0") }
        box?.infoJson = info(update = "committed", result = "committed 1.1.0 ocbm eec9db5 (from 1.0.0 ocbm f8f18c8)")
        waitUntil(2000) { session.phase.value == UpdatePhase.Updated("1.1.0") }
        assertEquals("the install is read again, for its new version", 1, updated.get())

        session.dismiss()
        assertEquals(UpdatePhase.Idle, session.phase.value)
    }

    @Test
    fun `an update that fails its self-test ends on the version the adapter kept, with its reason`() {
        start()
        sendIt()
        restart(info(update = "trial"))
        waitUntil(2000) { session.phase.value is UpdatePhase.Checking }
        // The self-test failed: the adapter puts 1.0.0 back and restarts again.
        restart(info(update = "rolled_back", result = "rolled_back 1.1.0 ocbm eec9db5: its start-up self-test failed (kept 1.0.0 ocbm f8f18c8)"))
        waitUntil(2000) { session.phase.value is UpdatePhase.Kept }
        assertEquals(UpdatePhase.Kept("1.0.0", "its start-up self-test failed"), session.phase.value)
        assertEquals(0, updated.get())
    }

    @Test
    fun `a refused package ends the update with the adapter's reason`() {
        start()
        val b = sendIt()
        b.infoJson = info(update = "refused", result = "refused 1.1.0 ocbm eec9db5: An update is already in its trial on this adapter.")
        waitUntil(2000) { session.phase.value is UpdatePhase.Refused }
        assertEquals(UpdatePhase.Refused("An update is already in its trial on this adapter."), session.phase.value)
    }

    @Test
    fun `nothing is sent while the car is moving or a call is on`() {
        start()
        waitUntil(2000) { session.offer.value is UpdateOffer.InPlace }
        notNow = "The car is moving. Park, then update the adapter."
        assertEquals(notNow, session.notNowReason())
        session.update()
        assertEquals(UpdatePhase.Failed("The update didn't start", "The car is moving. Park, then update the adapter."), session.phase.value)
        Thread.sleep(100) // shows that nothing goes out
        assertTrue(box!!.pushed.isEmpty())
    }

    @Test
    fun `a package that can't be had, sent or started fails, and the adapter is left as it was`() {
        val supplier = FakeSupplier(pkg, Result.failure(IOException("HTTP 404 from the release")))
        start(supplier)
        waitUntil(2000) { session.offer.value is UpdateOffer.InPlace }
        session.update()
        waitUntil(2000) { session.phase.value is UpdatePhase.Failed }
        assertEquals("Couldn't get the update", (session.phase.value as UpdatePhase.Failed).title)
        assertTrue(box!!.pushed.isEmpty())

        session.dismiss()
        supplier.bytes = Result.success(UpdateFixtures.PACKAGE_1_1_0)
        box!!.pushResult = Result.failure(IllegalStateException("push /tmp/ocbm-update.tgz: no answer to FILE_CLOSE"))
        session.update()
        waitUntil(2000) { session.phase.value is UpdatePhase.Failed }
        assertEquals("Couldn't send the update", (session.phase.value as UpdatePhase.Failed).title)
        assertTrue("nothing is started after a failed push", box!!.started.isEmpty())

        session.dismiss()
        box!!.pushResult = Result.success(Unit)
        box!!.ack = 1
        session.update()
        waitUntil(2000) { session.phase.value is UpdatePhase.Failed }
        assertEquals("The adapter didn't start the update", (session.phase.value as UpdatePhase.Failed).title)
    }

    @Test
    fun `a package that isn't the version offered is not sent`() {
        start(FakeSupplier(pkg.copy(version = "1.2.0")))
        waitUntil(2000) { session.offer.value is UpdateOffer.InPlace }
        session.update()
        waitUntil(2000) { session.phase.value is UpdatePhase.Failed }
        assertTrue((session.phase.value as UpdatePhase.Failed).body.startsWith("The package isn't OCBM 1.2.0 (it says 1.1.0)."))
        assertTrue(box!!.pushed.isEmpty())
    }

    @Test
    fun `an adapter not back with an outcome by the deadline did not update`() {
        start(timing = AdapterUpdateSession.Timing(pollMs = 20, deadlineMs = 500, startMs = 100))
        sendIt()
        box = null
        session.reset()
        waitUntil(3000) { session.phase.value is UpdatePhase.Failed }
        assertEquals("The update didn't happen", (session.phase.value as UpdatePhase.Failed).title)
    }

    @Test
    fun `an earlier update's outcome is not taken for this session's`() {
        box!!.infoJson = info(update = "committed", result = "committed 1.0.0 ocbm f8f18c8 (from 0.9.0 ocbm old)")
        start()
        waitUntil(2000) { session.offer.value is UpdateOffer.InPlace }
        assertEquals(UpdatePhase.Idle, session.phase.value)
    }

    @Test
    fun `the offer follows the install as it is read, and the free flash the adapter reports`() {
        install.value = null
        box!!.infoJson = info(freeKb = 300)
        start()
        Thread.sleep(100) // shows that nothing is offered before the install is known
        assertEquals(UpdateOffer.None, session.offer.value)
        install.value = OneFileInstall(InstallMode.TRIAL, "1.0.0")
        waitUntil(2000) { session.offer.value is UpdateOffer.RoundTrip }
        install.value = OneFileInstall(InstallMode.CONFIRMED, "1.0.0")
        waitUntil(2000) { session.offer.value is UpdateOffer.AfterThisDrive }
        session.update()
        Thread.sleep(100) // shows that the tap starts nothing while the install finishes
        assertEquals(UpdatePhase.Idle, session.phase.value)
        assertTrue(box!!.pushed.isEmpty())
    }

    @Test
    fun `with updates off, or an adapter without CAP_UPDATE, nothing is offered`() {
        start(enabled = false)
        Thread.sleep(200) // shows that the offer stays empty
        assertEquals(UpdateOffer.None, session.offer.value)

        box!!.takesUpdates = false
        start()
        Thread.sleep(200)
        assertEquals(UpdateOffer.None, session.offer.value)
    }

    private fun waitUntil(
        timeoutMs: Long,
        done: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > deadline) fail("timed out; phase ${session.phase.value}, offer ${session.offer.value}")
            Thread.sleep(10)
        }
    }
}
