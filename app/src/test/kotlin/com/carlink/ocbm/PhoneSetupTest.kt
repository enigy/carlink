package com.carlink.ocbm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private val timing =
        PhoneSetup.Timing(
            waitMs = 300,
            windowSeconds = 1,
            btStartMs = 600,
            btRetryMs = 100,
            forgetQuietMs = 1_500,
            restartPollMs = 20,
            restartDownMs = 600,
            reopenCheckMs = 200,
            windowTimeoutMs = 3_000,
        )
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
        // Bench 11-03: the window stayed open after the phone came, and the adapter stayed discoverable.
        waitUntil { box.windows == listOf(timing.windowSeconds, 0) }
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
        await<PhoneSetupPhase.BluetoothStarting>()
        assertEquals(1, await<PhoneSetupPhase.BluetoothDown>().status)
        assertTrue("asked again while it started", box.windows.size > 2)
        setup.later()
        assertEquals(PhoneSetupPhase.Waiting("CarLink-9C1A", noPhone = true), setup.phase.value)
    }

    @Test
    fun `a window refused while the adapter's Bluetooth starts is asked again, and opens once it answers`() {
        // The truck, 5 Nov: btd up ~15 s after power-on, and three refusals in that time read as Bluetooth failing.
        box.devices = "[]"
        box.windowStatus = 1
        setup.onSubscribed()
        await<PhoneSetupPhase.Prompt>()
        setup.pair()
        await<PhoneSetupPhase.BluetoothStarting>()
        box.windowStatus = 0
        await<PhoneSetupPhase.Pairing>()
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
    fun `a paired iPhone that forgot the adapter is asked to re-pair at once`() {
        // The 5 Nov bench: it answered six pages and reset each, and pairing was offered only after a minute's wait.
        box.devices = """["AA:BB:CC:DD:EE:FF"]"""
        setup.onSubscribed()
        waitUntil { box.reads.get() == 1 }
        setup.onBtPhase(Ocbm.BTP_BOND_LOST)
        assertEquals(PhoneSetupPhase.PhoneForgot("CarLink-9C1A"), setup.phase.value)
        setup.onPhoneConnected() // it was a still-paired phone after all: the question goes at once
        assertEquals(PhoneSetupPhase.None, setup.phase.value)
        setup.reset()
        setup.onSubscribed()
        waitUntil { box.reads.get() >= 3 } // the re-read on connect, then this session's
        setup.onBtPhase(Ocbm.BTP_BOND_LOST)
        setup.pair()
        await<PhoneSetupPhase.Pairing>()
        setup.onBtPhase(Ocbm.BTP_BOND_LOST)
        assertTrue("not over a pairing under way", setup.phase.value is PhoneSetupPhase.Pairing)
    }

    @Test
    fun `BOND_LOST right after this app's own Forget is the box's restart, not news`() {
        // 8 Nov bench: FORGET_DEVICE at 18:22:50.163, BTP_BOND_LOST 0.55 s later, and the screen asked for a re-pair.
        box.devices = """["AA:BB:CC:DD:EE:FF"]"""
        setup.onSubscribed()
        waitUntil { box.reads.get() == 1 }
        setup.onForgetSent()
        setup.onBtPhase(Ocbm.BTP_BOND_LOST)
        assertEquals(PhoneSetupPhase.None, setup.phase.value)
        Thread.sleep(timing.forgetQuietMs + 100)
        setup.onBtPhase(Ocbm.BTP_BOND_LOST)
        assertEquals("after the quiet time it is news again", PhoneSetupPhase.PhoneForgot("CarLink-9C1A"), setup.phase.value)
    }

    @Test
    fun `a window asked for right after a Forget waits for the wireless restart`() {
        // 8 Nov bench: opened 2.4 s after the forget, closed 0.7 s later by the restart; the first tap did nothing.
        box.devices = "[]"
        setup.onSubscribed()
        await<PhoneSetupPhase.Prompt>()
        box.wireless = true
        setup.onForgetSent()
        setup.pair()
        Thread.sleep(150)
        assertTrue("not before the restart has begun", box.windows.isEmpty())
        assertTrue("and the screen says what it waits for", setup.phase.value is PhoneSetupPhase.BluetoothStarting)
        box.wireless = false // btd down: the restart
        Thread.sleep(150)
        assertTrue("not while btd is down", box.windows.isEmpty())
        box.wireless = true // and up again
        await<PhoneSetupPhase.Pairing>()
        assertEquals(listOf(timing.windowSeconds), box.windows)
    }

    @Test
    fun `a window the restart closed again is opened once more`() {
        box.devices = "[]"
        setup.onSubscribed()
        await<PhoneSetupPhase.Prompt>()
        box.wireless = false
        setup.onForgetSent()
        setup.pair()
        Thread.sleep(100)
        box.wireless = true
        await<PhoneSetupPhase.Pairing>()
        box.open = false // the box closed it
        waitUntil { box.windows.size == 2 }
        assertEquals(listOf(timing.windowSeconds, timing.windowSeconds), box.windows)
        Thread.sleep(timing.reopenCheckMs + 100)
        assertEquals("once, not on every look", 2, box.windows.size)
    }

    @Test
    fun `a window the driver stopped right after a Forget is not opened again`() {
        // Stop within the 4 s check: the box must not be left discoverable while the screen shows the prompt.
        box.devices = "[]"
        setup.onSubscribed()
        await<PhoneSetupPhase.Prompt>()
        box.wireless = false
        setup.onForgetSent()
        setup.pair()
        Thread.sleep(100)
        box.wireless = true
        await<PhoneSetupPhase.Pairing>()
        setup.stop()
        box.open = false
        Thread.sleep(timing.reopenCheckMs + 200)
        assertEquals(listOf(timing.windowSeconds, 0), box.windows)
        assertTrue(setup.phase.value is PhoneSetupPhase.Prompt)
    }

    @Test
    fun `no restart seen within the down time opens anyway`() {
        box.devices = "[]"
        setup.onSubscribed()
        await<PhoneSetupPhase.Prompt>()
        box.wireless = true // the 2 s health ticks missed btd going down
        setup.onForgetSent()
        setup.pair()
        await<PhoneSetupPhase.Pairing>(timeoutMs = 2_000)
        assertEquals(listOf(timing.windowSeconds), box.windows)
    }

    @Test
    fun `after the phone has left, a window after a Forget waits for the restart and Bluetooth again`() {
        // 9 Nov bench: the iPhone left, then Forget and Add phone. Still taken as connected, the window skipped the
        // restart wait, got status 1 while btd restarted and gave up after two tries: "btd isn't reachable".
        box.devices = """["AA:BB:CC:DD:EE:FF"]"""
        setup.onSubscribed()
        waitUntil { box.reads.get() == 1 }
        setup.onPhoneConnected()
        setup.onPhoneLeft()
        box.wireless = true
        setup.onForgetSent()
        var status: Int? = -1
        val asked = scope.launch { status = setup.window(timing.windowSeconds) }
        Thread.sleep(150)
        assertTrue("not before the restart has begun", box.windows.isEmpty())
        box.wireless = false
        Thread.sleep(100)
        box.windowStatus = 1 // btd is still starting when the window goes out
        box.wireless = true
        waitUntil { box.windows.size >= 2 }
        box.windowStatus = 0
        waitUntil(2_000) { asked.isCompleted }
        assertEquals("tried again until btd answered", 0, status)
    }

    @Test
    fun `a second phone in the session clears pairing and stops the window's clock`() {
        // 9 Nov bench (eb41fcab): Add phone, then another iPhone paired and connected, and two minutes on the window
        // "ran out with no phone" over it: only the generation's first phone had raised onPhoneConnected.
        box.devices = "[]"
        setup.onSubscribed()
        await<PhoneSetupPhase.Prompt>()
        setup.onPhoneConnected() // the first phone
        setup.onPhoneLeft()
        setup.pair()
        await<PhoneSetupPhase.Pairing>()
        setup.onPhoneConnected() // the next phone, in the same session
        assertEquals(PhoneSetupPhase.None, setup.phase.value)
        Thread.sleep(timing.windowSeconds * 1_000L + 300)
        assertEquals("the window's clock stopped", PhoneSetupPhase.None, setup.phase.value)
    }

    @Test
    fun `a window request that never ends is given up as no answer`() {
        // 9 Nov bench: two opens btd applied never logged a status. Every request now ends, and says how.
        val quick = PhoneSetup(box, scope, timing.copy(windowTimeoutMs = 300))
        box.devices = "[]"
        quick.onSubscribed()
        waitUntil(timeoutMs = 1_000) { quick.phase.value is PhoneSetupPhase.Prompt }
        box.windowDelayMs = 10_000
        val started = System.currentTimeMillis()
        quick.pair()
        waitUntil(timeoutMs = 2_000) { quick.phase.value is PhoneSetupPhase.NotPaired }
        assertTrue("within its time limit", System.currentTimeMillis() - started < 1_500)
        assertEquals(PhoneSetup.NO_ANSWER, (quick.phase.value as PhoneSetupPhase.NotPaired).title)
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
    fun `the screen follows the adapter's name from a later MGMT_INFO`() {
        // Factory adapter, 31 Oct: the read at SUBSCRIBE said CarLink-0264, the iPhone listed CarLink-fd6c.
        box.devices = "[]"
        setup.onSubscribed()
        assertEquals("CarLink-9C1A", await<PhoneSetupPhase.Prompt>().name)
        setup.onAdapterName("CarLink-fd6c")
        assertEquals(PhoneSetupPhase.Prompt("CarLink-fd6c", newPhone = false), setup.phase.value)
        setup.pair()
        assertEquals("pairing names it too", "CarLink-fd6c", await<PhoneSetupPhase.Pairing>().name)
        val notPaired = await<PhoneSetupPhase.NotPaired>(timeoutMs = 3_000)
        assertEquals("CarLink-fd6c", notPaired.name)
        assertTrue(notPaired.body, notPaired.body.contains("CarLink-fd6c"))
        assertEquals(PhoneSetupPhase.None, PhoneSetup.renamed(PhoneSetupPhase.None, "CarLink-fd6c"))
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

        @Volatile var windowDelayMs = 0L

        override fun pairWindow(seconds: Int): Int? {
            windows += seconds
            if (windowDelayMs > 0) Thread.sleep(windowDelayMs)
            return windowStatus
        }

        override fun now(): Long = System.currentTimeMillis()

        @Volatile var wireless: Boolean? = null

        @Volatile var open: Boolean? = null

        override fun wirelessUp(): Boolean? = wireless

        override fun windowOpen(): Boolean? = open
    }
}
