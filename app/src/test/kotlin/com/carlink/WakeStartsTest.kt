package com.carlink

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The wake's starts and the undo, driven through [WakeStarts] itself with a scripted session (the Mac's 14 Nov review,
 * W2): the orderings it asked for, each able to fail if the wiring goes back to the 13 Nov C1 bug or loses a guard.
 */
class WakeStartsTest {
    private enum class S { DISCONNECTED, CONNECTING, STREAMING }

    /** How one start goes: where it waits (blocking like a HELLO, or suspended like the adapter search), and its end. */
    private data class Script(
        val block: CountDownLatch? = null,
        val suspendAt: CompletableDeferred<Unit>? = null,
        val answers: Boolean = true,
        val subscribeFirst: Boolean = false,
    )

    private class FakeSession : WakeStarts.Host {
        @Volatile var state = S.DISCONNECTED

        @Volatile var on = true

        @Volatile var listed = true

        @Volatile var subscribedNow = false

        val scripts = ConcurrentLinkedQueue<Script>()
        val begun = AtomicInteger()
        val stops = AtomicInteger()
        val hellos = CopyOnWriteArrayList<Long>()

        // The class under test, told of each SUBSCRIBE as CarlinkManager.startImpl tells it.
        @Volatile var starts: WakeStarts? = null

        fun subscribe() {
            subscribedNow = true
            starts?.sessionUp()
        }

        override fun stateName() = state.name

        // Run once by the first check that finds the session STREAMING: the wake job's own, after its start returned.
        @Volatile var afterStart: (() -> Unit)? = null

        override fun disconnected(): Boolean {
            if (state == S.STREAMING) afterStart?.also { afterStart = null }?.invoke()
            return state == S.DISCONNECTED
        }

        override fun connecting() = state == S.CONNECTING

        override fun subscribed() = subscribedNow

        override fun released() = false

        override fun displayOn() = on

        override fun adapterListed() = listed

        override fun cancelReconnect() = Unit

        override suspend fun start(helloMs: Long) {
            val s = scripts.poll() ?: Script()
            checkNotNull(currentCoroutineContext()[Job])
            begun.incrementAndGet()
            hellos += helloMs
            subscribedNow = false
            state = S.CONNECTING
            if (s.subscribeFirst) subscribe()
            s.block?.await() // not cancellable, as a blocking HELLO isn't
            s.suspendAt?.await() // cancellable
            if (!s.answers) return // no reply: the session stays CONNECTING until its error is handled
            subscribe()
            state = S.STREAMING
        }

        override fun stop() {
            stops.incrementAndGet()
            subscribedNow = false
            state = S.DISCONNECTED
        }

        override fun log(line: String) = Unit
    }

    private val session = FakeSession()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val starts = WakeStarts(scope, Mutex(), session, settleMs = 50).also { session.starts = it }

    @After
    fun end() = scope.cancel()

    private fun eventually(
        what: String,
        cond: () -> Boolean,
    ) {
        val until = System.currentTimeMillis() + 3_000
        while (!cond()) {
            check(System.currentTimeMillis() < until) { "never: $what" }
            Thread.sleep(5)
        }
    }

    private fun settled() = Thread.sleep(250)

    private fun off() {
        session.on = false
        starts.displayOff()
    }

    private fun on(nowMs: Long = 1) {
        session.on = true
        starts.displayOn(wasDormant = false, nowMs = nowMs)
    }

    @Test
    fun an_off_during_the_adapter_search_then_an_on_undoes_the_cut_start_and_makes_exactly_one_more() {
        session.scripts += Script(suspendAt = CompletableDeferred())
        on()
        eventually("the wake's start") { session.begun.get() == 1 }
        off()
        eventually("the undo") { session.stops.get() == 1 && session.state == S.DISCONNECTED }
        settled()
        assertEquals("the display is still off: no restart", 1, session.begun.get())
        on()
        eventually("the next wake streams") { session.state == S.STREAMING }
        settled()
        assertEquals(2, session.begun.get())
    }

    @Test
    fun an_off_after_subscribe_keeps_the_session_that_came_up() {
        session.scripts += Script(subscribeFirst = true, suspendAt = CompletableDeferred())
        on()
        eventually("the wake's start") { session.begun.get() == 1 }
        off()
        settled()
        assertEquals("not undone", 0, session.stops.get())
        assertEquals(S.CONNECTING, session.state)
        on()
        settled()
        assertEquals("the ON queues nothing that starts over it", 1, session.begun.get())
        assertNull("the ON started nothing: no wake is timed", starts.streaming(5_000))
    }

    @Test
    fun close_app_during_a_wake_never_restarts_it() {
        session.scripts += Script(suspendAt = CompletableDeferred())
        on()
        eventually("the wake's start") { session.begun.get() == 1 }
        starts.closing() // Close app: the display stays on
        eventually("the undo") { session.stops.get() == 1 }
        settled()
        assertEquals(1, session.begun.get())
        assertEquals(S.DISCONNECTED, session.state)
        assertNull("no wake is timed after Close app", starts.streaming(5_000))
    }

    @Test
    fun an_on_while_the_cut_start_still_blocks_gives_one_start_after_the_undo() {
        val hello = CountDownLatch(1)
        session.scripts += Script(block = hello, answers = false)
        on(nowMs = 1)
        eventually("the wake's start") { session.begun.get() == 1 }
        off()
        on(nowMs = 2) // CONNECTING: queued behind the cut start
        hello.countDown() // no reply: the start ends cut, before SUBSCRIBE
        eventually("the start after the undo streams") { session.state == S.STREAMING }
        settled()
        assertEquals(2, session.begun.get())
        assertEquals(1, session.stops.get())
        assertEquals("[startup] wake: display on to STREAMING 4998 ms (again after a display OFF undid its start)", starts.streaming(5_000))
    }

    @Test
    fun off_on_off_on_while_a_start_blocks_gives_one_start() {
        val hello = CountDownLatch(1)
        session.scripts += Script(block = hello, answers = false)
        on(nowMs = 1)
        eventually("the wake's start") { session.begun.get() == 1 }
        off()
        on(nowMs = 2)
        off()
        on(nowMs = 3)
        hello.countDown()
        eventually("one start streams") { session.state == S.STREAMING }
        settled()
        assertEquals(2, session.begun.get())
        assertEquals(1, session.stops.get())
    }

    @Test
    fun only_the_wake_s_own_start_waits_3_s_for_hello() {
        // A start under the lock (an attach) keeps 20 s even while a wake waits for the lock (W7).
        val hello = CountDownLatch(1)
        session.scripts += Script(block = hello)
        scope.launch { starts.startIfDisconnected("Adapter attached") }
        eventually("the attach's start") { session.begun.get() == 1 }
        starts.displayOn(wasDormant = true, nowMs = 1) // queued behind it: the display-off teardown's case
        hello.countDown()
        eventually("it streams") { session.state == S.STREAMING }
        settled()
        assertEquals(listOf(20_000L), session.hellos.toList())
        session.state = S.DISCONNECTED
        on(nowMs = 2)
        eventually("the wake's start") { session.hellos.size == 2 }
        assertEquals(DisplayOff.WAKE_HELLO_MS, session.hellos[1])
        session.state = S.DISCONNECTED
        session.listed = false
        off()
        on(nowMs = 3)
        eventually("the settled start") { session.hellos.size == 3 }
        assertEquals("not listed: after the settle, the usual wait", 20_000L, session.hellos[2])
    }

    @Test
    fun an_on_behind_the_display_off_teardown_queues_the_start_that_follows_it() {
        // The grace end's stop holds the lifecycle lock while the display comes back (wasDormant): only the queue starts.
        val lock = Mutex()
        val queued = WakeStarts(scope, lock, session, settleMs = 50).also { session.starts = it }
        session.state = S.STREAMING
        runBlocking { lock.lock() }
        queued.displayOn(wasDormant = true, nowMs = 1)
        session.state = S.DISCONNECTED // the stop ends
        lock.unlock()
        eventually("the queued start") { session.state == S.STREAMING }
        assertEquals(1, session.begun.get())
        assertEquals("[startup] wake: display on to STREAMING 4999 ms (after the start or stop in flight)", queued.streaming(5_000))
    }

    @Test
    fun close_app_clears_a_cut_so_nothing_starts_after_it() {
        val hello = CountDownLatch(1)
        session.scripts += Script(block = hello, answers = false)
        on(nowMs = 1)
        eventually("the wake's start") { session.begun.get() == 1 }
        off() // cut
        starts.closing() // Close app: the cut no longer counts
        on(nowMs = 2) // CONNECTING: without the cut, nothing is queued
        hello.countDown()
        eventually("the undo") { session.stops.get() == 1 }
        settled()
        assertEquals("no start after Close app", 1, session.begun.get())
    }

    @Test
    fun an_on_while_streaming_times_no_wake() {
        on(nowMs = 1)
        eventually("streaming") { session.state == S.STREAMING }
        assertEquals("[startup] wake: display on to STREAMING 999 ms (at once, the adapter listed)", starts.streaming(1_000))
        off()
        on(nowMs = 2) // streaming already: nothing starts
        settled()
        assertNull(starts.streaming(5_000))
    }

    @Test
    fun an_off_after_the_wake_came_up_but_before_its_job_ended_is_no_cut() {
        // W (the 2.6.0 review): the start has returned and its ownStart is cleared, but the wake's job still runs.
        session.afterStart = { starts.displayOff() }
        on(nowMs = 1)
        eventually("streaming") { session.state == S.STREAMING }
        settled()
        on(nowMs = 2) // streaming: nothing starts, and no wake is timed
        settled()
        assertNull(starts.streaming(5_000))
        assertEquals(1, session.begun.get())
    }

    @Test
    fun an_off_against_a_wake_queued_behind_the_grace_end_stop_still_cuts_it() {
        // The stop holds the lock, and its old client reads subscribed until its CT_STOP: not this class's start (round 3).
        val lock = Mutex()
        val queued = WakeStarts(scope, lock, session, settleMs = 50).also { session.starts = it }
        session.state = S.STREAMING
        session.subscribedNow = true
        runBlocking { lock.lock() }
        session.on = true
        queued.displayOn(wasDormant = true, nowMs = 1) // queued behind the stop
        session.on = false
        queued.displayOff() // cut, though the old client reads subscribed
        session.on = true
        queued.displayOn(wasDormant = false, nowMs = 2) // still not DISCONNECTED: queued again only for the cut
        session.state = S.DISCONNECTED // the stop ends
        session.subscribedNow = false
        lock.unlock()
        eventually("the start after the stop") { session.state == S.STREAMING }
        assertEquals(1, session.begun.get())
    }

    @Test
    fun a_start_that_comes_up_clears_an_older_cut() {
        val slow = WakeStarts(scope, Mutex(), session, settleMs = 1_000).also { session.starts = it }
        session.listed = false
        session.on = true
        slow.displayOn(wasDormant = false, nowMs = 1) // not listed: the settle first
        session.on = false
        slow.displayOff() // cut during the settle: no start to undo
        session.on = true
        slow.displayOn(wasDormant = false, nowMs = 2)
        eventually("streaming") { session.state == S.STREAMING }
        slow.streaming(3_000)
        session.on = false
        slow.displayOff() // streaming: nothing in flight
        session.on = true
        slow.displayOn(wasDormant = false, nowMs = 4)
        settled()
        assertEquals("no stale cut queues a start", 1, session.begun.get())
        assertNull("nor times a wake", slow.streaming(9_000))
    }

    @Test
    fun a_reconnect_or_reset_start_that_comes_up_clears_a_cut() {
        val slow = WakeStarts(scope, Mutex(), session, settleMs = 1_000).also { session.starts = it }
        session.listed = false
        session.on = true
        slow.displayOn(wasDormant = false, nowMs = 1) // not listed: the settle first
        session.on = false
        slow.displayOff() // cut during the settle: no start to undo
        runBlocking { session.start(helloMs = 20_000) } // a reconnect's or a Reset's start, not this class's, comes up
        session.on = true
        slow.displayOn(wasDormant = false, nowMs = 4)
        settled()
        assertEquals("no stale cut queues a start", 1, session.begun.get())
        assertNull("nor times a wake", slow.streaming(9_000))
    }

    @Test
    fun after_a_wake_that_came_up_an_off_against_a_wake_queued_behind_the_grace_end_stop_still_cuts_it() {
        // The wake's own start is over: its client reading subscribed later is the old session's, during the stop.
        val lock = Mutex()
        val queued = WakeStarts(scope, lock, session, settleMs = 50).also { session.starts = it }
        session.on = true
        queued.displayOn(wasDormant = false, nowMs = 1)
        eventually("the wake streams") { session.state == S.STREAMING }
        settled()
        queued.streaming(1_000)
        session.on = false
        queued.displayOff() // streaming: nothing in flight
        runBlocking { lock.lock() } // the display-off wait's stop
        session.on = true
        queued.displayOn(wasDormant = true, nowMs = 2) // queued behind the stop
        session.on = false
        queued.displayOff() // cut
        session.on = true
        queued.displayOn(wasDormant = false, nowMs = 3) // still not DISCONNECTED: queued again only for the cut
        session.state = S.DISCONNECTED // the stop ends
        session.subscribedNow = false
        lock.unlock()
        eventually("the start after the stop") { session.begun.get() == 2 && session.state == S.STREAMING }
    }

    @Test
    fun the_hold_runs_on_the_injected_clock_and_a_sleep_ends_it() {
        var now = 10_000L
        val box = BoxTeardown({ now }, settleMs = 6_000)
        assertEquals("nothing stopped", 0L, box.holdMs())
        box.stopped()
        now = 11_200
        assertEquals(4_800L, box.holdMs())
        now += 8 * 3_600_000L // the head unit slept the night: elapsedRealtime kept counting
        assertEquals(0L, box.holdMs())
    }

    @Test
    fun a_start_reaching_hello_waits_out_six_seconds_from_the_last_ct_stop() {
        // The grace end stops at 10.0 s; the ON lands at 10.5 s while that stop still runs; the start reaches HELLO at
        // 11.2 s and holds until 16.0 s, six seconds from that CT_STOP (W1).
        assertEquals(4_800L, holdBeforeHello(nowMs = 11_200, stoppedAtMs = 10_000, settleMs = 6_000))
        assertEquals("no stop of ours", 0L, holdBeforeHello(nowMs = 11_200, stoppedAtMs = 0, settleMs = 6_000))
        assertEquals("long ago", 0L, holdBeforeHello(nowMs = 30_000, stoppedAtMs = 10_000, settleMs = 6_000))
    }
}
