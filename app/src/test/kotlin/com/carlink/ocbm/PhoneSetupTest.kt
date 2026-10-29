package com.carlink.ocbm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Pairing an iPhone on the connecting screen: what MGMT_INFO says, what the screen leads with, and the pairing window. */
@RunWith(RobolectricTestRunner::class)
class PhoneSetupTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val box = FakeBox()
    private val timing = PhoneSetup.Timing(waitMs = 300, windowSeconds = 1)
    private val setup = PhoneSetup(box, scope, timing)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `MGMT_INFO's devices are the bonded phones, and a box without the key says nothing`() {
        val none = PairedPhones.parse("""{"name":"CarLink-9C1A","devices":[]}""")!!
        assertEquals("CarLink-9C1A", none.name)
        assertEquals(emptyList<String>(), none.devices)
        assertTrue(none.none)
        val one = PairedPhones.parse("""{"name":"CarLink-9C1A","devices":["AA:BB:CC:DD:EE:FF"]}""")!!
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), one.devices)
        assertFalse(one.none)
        val several = PairedPhones.parse("""{"devices":["AA:BB:CC:DD:EE:01","AA:BB:CC:DD:EE:02",""]}""")!!
        assertEquals(2, several.devices?.size)
        assertNull("no name", several.name)
        val older = PairedPhones.parse("""{"name":"CarLink-9C1A","transport":"wireless"}""")!!
        assertNull("unknown, not empty", older.devices)
        assertFalse(older.none)
        assertNull(PairedPhones.parse("not json"))
    }

    @Test
    fun `the screen leads with pairing only when no phone is paired`() {
        assertEquals(PhoneSetupPhase.Prompt("CarLink-9C1A", newPhone = false), PhoneSetup.firstPhase(PairedPhones("CarLink-9C1A", emptyList())))
        assertEquals(PhoneSetupPhase.Prompt(PhoneSetup.DEFAULT_NAME, newPhone = false), PhoneSetup.firstPhase(PairedPhones(null, emptyList())))
        assertEquals(PhoneSetupPhase.None, PhoneSetup.firstPhase(PairedPhones("CarLink-9C1A", listOf("AA:BB:CC:DD:EE:FF"))))
        assertEquals("a box that doesn't report its phones waits as before", PhoneSetupPhase.None, PhoneSetup.firstPhase(PairedPhones("CarLink-9C1A", null)))
        assertEquals(PhoneSetupPhase.None, PhoneSetup.firstPhase(null))
    }

    @Test
    fun `no phone paired - pair, follow the phone, and carry on when it connects`() {
        box.devices = "[]"
        setup.onSubscribed()
        await<PhoneSetupPhase.Prompt>()
        assertTrue(setup.noPhonePaired)
        setup.pair()
        val pairing = await<PhoneSetupPhase.Pairing>()
        assertEquals("CarLink-9C1A", pairing.name)
        assertEquals(listOf(timing.windowSeconds), box.windows)
        setup.onBtPhase(Ocbm.BTP_AUTHENTICATING)
        assertEquals("Pairing with the iPhone...", (setup.phase.value as PhoneSetupPhase.Pairing).progress)
        setup.onPairingCode("123456")
        assertEquals("123456", (setup.phase.value as PhoneSetupPhase.Pairing).code)
        val reads = box.reads.get()
        box.devices = """["AA:BB:CC:DD:EE:FF"]"""
        setup.onPhoneConnected()
        assertEquals(PhoneSetupPhase.None, setup.phase.value)
        waitUntil { box.reads.get() > reads }
        waitUntil { !setup.noPhonePaired }
    }

    @Test
    fun `a window that runs out offers to try again`() {
        box.devices = "[]"
        setup.onSubscribed()
        await<PhoneSetupPhase.Prompt>()
        setup.pair()
        await<PhoneSetupPhase.Pairing>()
        val ended = await<PhoneSetupPhase.NotPaired>(timeoutMs = 3_000)
        assertEquals(PhoneSetup.NOT_PAIRED, ended.title)
        setup.pair()
        await<PhoneSetupPhase.Pairing>()
        assertEquals(2, box.windows.size)
    }

    @Test
    fun `a pairing window refused means the adapter's Bluetooth didn't start`() {
        box.devices = "[]"
        box.windowStatus = 1
        setup.onSubscribed()
        await<PhoneSetupPhase.Prompt>()
        setup.pair()
        assertEquals(1, await<PhoneSetupPhase.BluetoothDown>().status)
        setup.later()
        assertEquals(PhoneSetupPhase.Waiting("CarLink-9C1A", noPhone = true), setup.phase.value)
    }

    @Test
    fun `no answer to the pairing window is try again, not Bluetooth`() {
        box.devices = "[]"
        box.windowStatus = null
        setup.onSubscribed()
        await<PhoneSetupPhase.Prompt>()
        setup.pair()
        assertEquals(PhoneSetup.NO_ANSWER, await<PhoneSetupPhase.NotPaired>().title)
    }

    @Test
    fun `SET_POLICY that never comes back 0 means the adapter's Bluetooth didn't start`() {
        box.devices = """["AA:BB:CC:DD:EE:FF"]"""
        setup.onSubscribed()
        waitUntil { box.reads.get() == 1 }
        setup.onPolicyFailed(null)
        assertEquals("no answer is not a refusal", PhoneSetupPhase.None, setup.phase.value)
        setup.onPolicyFailed(1)
        assertEquals(1, (setup.phase.value as PhoneSetupPhase.BluetoothDown).status)
    }

    @Test
    fun `phones paired but none comes - keep waiting, then offer to pair a new one`() {
        box.devices = """["AA:BB:CC:DD:EE:FF"]"""
        setup.onSubscribed()
        waitUntil { box.reads.get() == 1 }
        assertEquals("waiting comes first", PhoneSetupPhase.None, setup.phase.value)
        assertFalse(setup.noPhonePaired)
        val waiting = await<PhoneSetupPhase.Waiting>(timeoutMs = 2_000)
        assertFalse(waiting.noPhone)
        setup.pairNew()
        assertEquals(PhoneSetupPhase.Prompt("CarLink-9C1A", newPhone = true), setup.phase.value)
        setup.later()
        assertEquals(PhoneSetupPhase.Waiting("CarLink-9C1A", noPhone = false), setup.phase.value)
    }

    @Test
    fun `a phone that comes in time leaves the waiting alone`() {
        box.devices = """["AA:BB:CC:DD:EE:FF"]"""
        setup.onSubscribed()
        waitUntil { box.reads.get() == 1 }
        setup.onPhoneConnected()
        Thread.sleep(timing.waitMs + 200)
        assertEquals(PhoneSetupPhase.None, setup.phase.value)
    }

    @Test
    fun `an older box that doesn't report its phones keeps today's waiting`() {
        box.devices = null
        setup.onSubscribed()
        waitUntil { box.reads.get() == 1 }
        Thread.sleep(timing.waitMs + 200)
        assertEquals(PhoneSetupPhase.None, setup.phase.value)
        assertFalse(setup.noPhonePaired)
    }

    @Test
    fun `a box that pairs without a window just says what to pick`() {
        box.devices = "[]"
        box.gates = false
        setup.onSubscribed()
        await<PhoneSetupPhase.Prompt>()
        setup.pair()
        assertNull(await<PhoneSetupPhase.Pairing>().endsAtMs)
        assertTrue("no MGMT_PAIR_WINDOW", box.windows.isEmpty())
    }

    @Test
    fun `stop closes the window and goes back to the prompt`() {
        box.devices = "[]"
        setup.onSubscribed()
        await<PhoneSetupPhase.Prompt>()
        setup.pair()
        await<PhoneSetupPhase.Pairing>()
        setup.stop()
        assertEquals(PhoneSetupPhase.Prompt("CarLink-9C1A", newPhone = false), setup.phase.value)
        waitUntil { box.windows.lastOrNull() == 0 }
    }

    @Test
    fun `the session ending clears it all`() {
        box.devices = "[]"
        setup.onSubscribed()
        await<PhoneSetupPhase.Prompt>()
        setup.reset()
        assertEquals(PhoneSetupPhase.None, setup.phase.value)
        assertFalse(setup.noPhonePaired)
    }

    private inline fun <reified T : PhoneSetupPhase> await(timeoutMs: Long = 1_000): T {
        waitUntil(timeoutMs) { setup.phase.value is T }
        return setup.phase.value as T
    }

    private fun waitUntil(
        timeoutMs: Long = 1_000,
        done: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > deadline) fail("timed out; phase ${setup.phase.value}")
            Thread.sleep(10)
        }
    }

    /** The adapter: MGMT_INFO with [devices] (a JSON array, or null for a box without the key), and its pairing window. */
    private class FakeBox : PhoneSetup.Box {
        @Volatile var devices: String? = "[]"

        @Volatile var windowStatus: Int? = 0

        @Volatile var gates = true
        val windows = CopyOnWriteArrayList<Int>()
        val reads = AtomicInteger()

        override fun info(): String {
            reads.incrementAndGet()
            return """{"name":"CarLink-9C1A"${devices?.let { ""","devices":$it""" }.orEmpty()}}"""
        }

        override val gatesPairing: Boolean get() = gates

        override fun pairWindow(seconds: Int): Int? {
            windows += seconds
            return windowStatus
        }

        override fun now(): Long = System.currentTimeMillis()
    }
}
