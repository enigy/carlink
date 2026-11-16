package com.carlink

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The adapter's attaches, counted: a wait ends at the first one after those it had seen, and never misses one between. */
class UsbAttachesTest {
    private val attaches = UsbAttaches()

    @Test
    fun `an attach during the wait ends it at once`() =
        runBlocking {
            val since = attaches.seen
            val wait = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { attaches.await(since, timeoutMs = 10_000) }
            attaches.attached(atMs = 5_200)
            assertTrue(wait.await())
            assertEquals(5_200L, attaches.lastAtMs)
        }

    @Test
    fun `an attach between the look and the wait is not missed`() =
        runBlocking {
            val since = attaches.seen // the look: the adapter not listed
            attaches.attached(atMs = 300) // it attaches before the wait begins
            assertTrue(attaches.await(since, timeoutMs = 10_000))
        }

    @Test
    fun `with no attach the wait ends at its time, and an older attach doesn't count`() =
        runBlocking {
            assertFalse(attaches.await(attaches.seen, timeoutMs = 50))
            attaches.attached(atMs = 100)
            assertFalse("seen before the wait", attaches.await(attaches.seen, timeoutMs = 50))
            assertFalse("no time to wait", attaches.await(attaches.seen, timeoutMs = 0))
        }
}
