package com.carlink.ocbm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/** Settings › Adapter's "Remove stock CarPlay" (Scott, 2 Nov): checked, asked, and only then confirmed. */
@RunWith(RobolectricTestRunner::class)
class StockRemoverTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var blocked: String? = null

    @Volatile private var ack: Int? = 0
    private val confirms = AtomicInteger()

    private val remover =
        StockRemover(
            proven = { blocked },
            confirm = {
                confirms.incrementAndGet()
                ack
            },
            scope = scope,
        )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a proven trial is asked, and Yes sends the install confirm once`() {
        remover.ask()
        waitFor(StockRemoval.Asking)
        assertEquals("nothing is sent before Yes", 0, confirms.get())
        remover.remove()
        waitFor(StockRemoval.Idle)
        assertEquals(1, confirms.get())
        remover.remove()
        assertEquals("Yes counts once", 1, confirms.get())
    }

    @Test
    fun `No sends nothing`() {
        remover.ask()
        waitFor(StockRemoval.Asking)
        remover.dismiss()
        assertEquals(StockRemoval.Idle, remover.state.value)
        assertEquals(0, confirms.get())
    }

    @Test
    fun `a trial that hasn't proven itself says why, and is never asked`() {
        blocked = finishBlocked("FAILED after 60 s: ocbmd is not running", carriedCarPlay = true)
        remover.ask()
        waitUntil { remover.state.value is StockRemoval.NotNow }
        assertEquals(StockRemoval.NotNow(blocked.orEmpty()), remover.state.value)
        remover.remove()
        assertEquals(0, confirms.get())
        remover.dismiss()
        assertEquals(StockRemoval.Idle, remover.state.value)
    }

    @Test
    fun `a confirm the adapter refuses or doesn't answer is said so`() {
        ack = 1
        remover.ask()
        waitFor(StockRemoval.Asking)
        remover.remove()
        waitFor(StockRemoval.Failed("The adapter refused it. Nothing was changed."))

        ack = null
        remover.dismiss()
        remover.ask()
        waitFor(StockRemoval.Asking)
        remover.remove()
        waitFor(StockRemoval.Failed("The adapter didn't answer. Nothing was changed."))
    }

    @Test
    fun `the session ending forgets the question`() {
        remover.ask()
        waitFor(StockRemoval.Asking)
        remover.reset()
        remover.remove()
        assertEquals(StockRemoval.Idle, remover.state.value)
        assertEquals(0, confirms.get())
    }

    private fun waitFor(state: StockRemoval) = waitUntil { remover.state.value == state }

    private fun waitUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2000
        while (!done()) {
            if (System.currentTimeMillis() > deadline) fail("timed out; state ${remover.state.value}")
            Thread.sleep(10)
        }
    }
}
