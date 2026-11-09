package com.carlink.usb

import com.enigy.carlink2.bridge.BridgeContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The USB permission prompt from the first ask to its answer: one owner of the prompt flag, and a late OK picked up. */
@RunWith(RobolectricTestRunner::class)
class UsbPermissionWaitTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flag = CopyOnWriteArrayList<Boolean>()
    private val wait =
        UsbPermissionWait(
            scope,
            UsbPermissionWait.Timing(gracePolls = 1, pollMs = 10, promptWaitMs = 100, watchMs = 2_000, watchPollMs = 20, now = System::currentTimeMillis),
        ) { flag += it }

    @After
    fun tearDown() = scope.cancel()

    private class Port : UsbPermissionWait.Port {
        @Volatile var granted = false

        @Volatile var result = BridgeContract.PERMISSION_PENDING
        val asks = AtomicInteger()

        override fun hasPermission(): Boolean = granted

        override fun requestPermission(): Int {
            asks.incrementAndGet()
            return if (granted) BridgeContract.PERMISSION_GRANTED else result
        }
    }

    @Test
    fun `a granted adapter is no prompt at all`() =
        runBlocking {
            val port = Port().apply { granted = true }
            assertEquals(UsbPermissionWait.Outcome.GRANTED, wait.await(port, "dev"))
            assertTrue(flag.isEmpty())
        }

    @Test
    fun `a denial clears the flag`() =
        runBlocking {
            val port = Port().apply { result = BridgeContract.PERMISSION_DENIED }
            assertEquals(UsbPermissionWait.Outcome.CLOSED, wait.await(port, "dev"))
            assertEquals(listOf(true, false), flag.toList())
        }

    @Test
    fun `a prompt handed to the watch keeps the flag set until the late OK, then connects once`() =
        runBlocking {
            val port = Port()
            val grants = AtomicInteger()
            assertEquals(UsbPermissionWait.Outcome.PENDING, wait.await(port, "dev"))
            assertEquals("never false between the wait and the watch", listOf(true), flag.toList())
            wait.watch(port, "dev", stillWanted = { true }) { grants.incrementAndGet() }
            assertTrue(wait.watching)
            // A retry while it watches is the same device: the running watch is kept, and its flag with it.
            wait.watch(port, "dev", stillWanted = { true }) { grants.incrementAndGet() }
            Thread.sleep(100)
            assertFalse("still true while the prompt is open", false in flag)
            port.granted = true
            waitUntil { grants.get() == 1 }
            waitUntil { !wait.watching }
            assertEquals(listOf(true, true, false), flag.toList())
            Thread.sleep(100)
            assertEquals("once", 1, grants.get())
        }

    @Test
    fun `a replaced watch can't clear the flag under its successor`() =
        runBlocking {
            val first = Port()
            val second = Port()
            wait.watch(first, "old", stillWanted = { true }) {}
            wait.watch(second, "new", stillWanted = { true }) {}
            Thread.sleep(150) // the first watch's cancellation has run its end by now
            assertFalse("the flag stays up for the new watch", false in flag)
            second.result = BridgeContract.PERMISSION_NO_DEVICE
            waitUntil { !wait.watching }
            assertEquals(false, flag.last())
        }

    @Test
    fun `abandon lets the flag go`() =
        runBlocking {
            val port = Port()
            assertEquals(UsbPermissionWait.Outcome.PENDING, wait.await(port, "stick"))
            wait.abandon()
            assertEquals(listOf(true, false), flag.toList())
        }

    private fun waitUntil(
        timeoutMs: Long = 2_000,
        done: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > deadline) fail("timed out; flag $flag")
            Thread.sleep(10)
        }
    }
}
