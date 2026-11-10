package com.carlink.usb

import android.content.Intent
import com.enigy.carlink2.bridge.BridgeContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One bridge update from Carlink's side: the call, the bridge's steps, the confirm screen, and the bridge coming back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BridgeUpdateRunTest {
    private val pin = PinnedBridge("1.0.9", 10, "https://github.com/enigy/carlink/releases/download/v224/carlink_bridge-1.0.9.apk", 2_800_000, "ab".repeat(32))
    private val steps = mutableListOf<BridgeUpdateStep>()
    private val bridge = FakeBridge()
    private val port = FakePort()
    private val timing = BridgeUpdateRun.Timing(rebindMs = 200, pollMs = 1, oldVersionGraceMs = 20)

    private fun run(): BridgeUpdateStep = runBlocking { BridgeUpdateRun(port, pin, { steps += it }, timing).run() }

    @Test
    fun `the bridge downloads and installs, its process ends, and the new one answers on the pinned version`() {
        bridge.script = { events ->
            events.onState(BridgeContract.UPDATE_DOWNLOADING, pin.url)
            events.onState(BridgeContract.UPDATE_CHECKING, null)
            events.onState(BridgeContract.UPDATE_INSTALLING, null)
            port.installed = 10
            bridge.die()
        }
        assertEquals(BridgeUpdateStep.Updated("1.0.9"), run())
        assertEquals(pin.json(), bridge.asked.single())
        assertEquals(
            listOf(
                BridgeUpdateStep.Asking,
                BridgeUpdateStep.Downloading,
                BridgeUpdateStep.Checking,
                BridgeUpdateStep.Installing,
                BridgeUpdateStep.Restarting,
                BridgeUpdateStep.Updated("1.0.9"),
            ),
            steps,
        )
        assertTrue("bound again after the death", port.binds >= 2)
    }

    @Test
    fun `the system's confirm screen, handed over by the bridge, is started by Carlink`() {
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        bridge.script = { events ->
            events.onUserAction(confirm)
            port.installed = 10
            events.onResult(true, "Bridge 1.0.9 installed")
            bridge.die()
        }
        assertEquals(BridgeUpdateStep.Updated("1.0.9"), run())
        assertSame(confirm, port.started.single())
        assertTrue(BridgeUpdateStep.Confirming in steps)
    }

    @Test
    fun `a bridge not allowed to install apps is asked nothing`() {
        bridge.mayInstall = false
        assertEquals(BridgeUpdateStep.NotAllowed, run())
        assertTrue(bridge.asked.isEmpty())
    }

    @Test
    fun `the bridge's refusal is the outcome, in its words`() {
        bridge.script = { events -> events.onResult(false, "The download's SHA-256 isn't the pinned one") }
        assertEquals(BridgeUpdateStep.Failed("The download's SHA-256 isn't the pinned one"), run())
    }

    @Test
    fun `a bridge that dies mid-update and comes back on its old version is a failure, after a re-bind and a version check`() {
        bridge.script = { events ->
            events.onState(BridgeContract.UPDATE_DOWNLOADING, pin.url)
            bridge.die()
        }
        val end = run()
        assertTrue(end.toString(), end is BridgeUpdateStep.Failed && end.message.contains("version code 9"))
        assertTrue("bound again after the death", port.binds >= 2)
        assertTrue(BridgeUpdateStep.Restarting in steps)
    }

    @Test
    fun `a confirm screen Carlink can't start ends the update`() {
        port.canStart = false
        bridge.script = { events -> events.onUserAction(Intent("x")) }
        val end = run()
        assertTrue(end.toString(), end is BridgeUpdateStep.Failed)
        assertFalse(port.installed == 10L)
    }

    @Test
    fun `no bridge, no update`() {
        port.answers = false
        assertEquals(BridgeUpdateStep.Failed("The USB bridge isn't responding"), run())
    }

    private class FakeBridge : BridgeUpdateRun.Bridge {
        var mayInstall = true
        val asked = mutableListOf<String>()
        var script: (BridgeUpdateRun.Events) -> Unit = {}
        private var death: (() -> Unit)? = null

        override fun canInstallPackages(): Boolean = mayInstall

        override fun installUpdate(
            json: String,
            events: BridgeUpdateRun.Events,
        ) {
            asked += json
            script(events)
        }

        override fun onDeath(block: () -> Unit): Boolean {
            death = block
            return true
        }

        fun die() {
            death?.invoke()
        }
    }

    private inner class FakePort : BridgeUpdateRun.Port {
        var installed = 9L
        var answers = true
        var canStart = true
        var binds = 0
        val started = mutableListOf<Intent>()

        override suspend fun bridge(): BridgeUpdateRun.Bridge? {
            binds++
            return if (answers) bridge else null
        }

        override fun installedVersionCode(): Long = installed

        override fun startConfirm(confirm: Intent): Boolean {
            started += confirm
            return canStart
        }

        override fun log(message: String) = Unit
    }
}
