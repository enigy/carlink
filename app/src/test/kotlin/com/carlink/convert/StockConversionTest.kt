package com.carlink.convert

import com.carlink.ocbm.FakeTransport
import com.carlink.protocol.MessageSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/** Converting a stock adapter, against a fake adapter: what is sent, what is offered, and each way it ends. */
@RunWith(RobolectricTestRunner::class)
class StockConversionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val images = FakeImages("an OCBM installer".toByteArray())
    private val searches = AtomicInteger()
    private val timing = StockConversion.Timing(readMs = 400, heartbeatMs = 50, deadlineMs = 700, searchMs = 200)
    private val conversion = StockConversion(images, conversionOn = true, scope = scope, searchAgain = { searches.incrementAndGet() }, timing = timing)
    private val adapter = FakeTransport()

    @After
    fun tearDown() {
        conversion.reset()
        scope.cancel()
    }

    @Test
    fun `with the switch off a stock adapter is only recognised, never opened`() {
        val off = StockConversion(images, conversionOn = false, scope = scope, searchAgain = {}, timing = timing)
        assertFalse(off.claim("1314:1520"))
        assertEquals(ConversionPhase.StickOnly("1314:1520"), off.phase.value)
        off.dismiss()
        assertEquals(ConversionPhase.Declined, off.phase.value)
        assertFalse(off.claim("1314:1520"))
        assertEquals(ConversionPhase.Declined, off.phase.value)
    }

    @Test
    fun `Open goes out first, then a heartbeat every interval`() {
        attach()
        val sent = writtenFrames(waitMs = 300)
        assertEquals(0x01, sent.first().first)
        assertTrue("heartbeats: ${sent.map { it.first }}", sent.drop(1).count { it.first == 0xAA } >= 3)
    }

    @Test
    fun `an A15W on 2025_10_15_1127 is offered the conversion`() {
        attachAndAnswer()
        val offered = awaitPhase<ConversionPhase.Offered>()
        assertEquals(Offer.CONVERT, offered.offer)
        assertEquals(StockInfo("2025.10.15.1127", "A15W"), offered.info)
        assertFalse("the link is held", conversion.claim("1314:1520"))
    }

    @Test
    fun `other firmware is told to update first, another model is turned away`() {
        attachAndAnswer(version = "2025.02.25.1521CAY")
        assertEquals(Offer.UPDATE_FIRST, awaitPhase<ConversionPhase.Offered>().offer)
        adapter.killLink()
        val other = FakeTransport()
        assertTrue(conversion.claim("1314:1521"))
        conversion.attach(other, "1314:1521")
        other.feed(frame(0xCC, "2025.10.15.1127CAY") + frame(0x19, """{"productType":"A15H"}"""))
        assertEquals(Offer.UNSUPPORTED, awaitPhase<ConversionPhase.Offered>().offer)
    }

    @Test
    fun `an adapter that never answers is not offered the conversion`() {
        attach()
        val offered = awaitPhase<ConversionPhase.Offered>(timeoutMs = 2_000)
        assertEquals(Offer.UNSUPPORTED, offered.offer)
        assertEquals(StockInfo(), offered.info)
    }

    @Test
    fun `no installer until the bench copy arrives, then check again offers the conversion`() {
        val bytes = images.image
        images.image = null
        attachAndAnswer()
        assertEquals(Offer.NO_IMAGE, awaitPhase<ConversionPhase.Offered>().offer)
        conversion.recheck()
        assertEquals(Offer.NO_IMAGE, (conversion.phase.value as ConversionPhase.Offered).offer)
        images.image = bytes
        conversion.recheck()
        assertEquals(Offer.CONVERT, (conversion.phase.value as ConversionPhase.Offered).offer)
    }

    @Test
    fun `convert sends the installer whole, with no heartbeat inside it, then waits for the restart`() {
        attachAndAnswer()
        awaitPhase<ConversionPhase.Offered>()
        conversion.convert()
        awaitPhase<ConversionPhase.Installing>()
        val files = writtenFrames(waitMs = 150).filter { it.first == 0x99 }
        assertEquals(1, files.size)
        val expected = MessageSerializer.serializeFile(InstallerImage.SEND_PATH, images.image!!)
        assertArrayEquals(expected.copyOfRange(16, expected.size), files.single().second)
        adapter.killLink()
        assertEquals(ConversionPhase.Restarting, conversion.phase.value)
        waitUntil(1_000) { searches.get() >= 1 }
        conversion.reset() // the manager found the OCBM adapter
        assertEquals(ConversionPhase.Idle, conversion.phase.value)
    }

    @Test
    fun `the adapter back on Carlinkit firmware after the installer has not converted`() {
        attachAndAnswer()
        awaitPhase<ConversionPhase.Offered>()
        conversion.convert()
        awaitPhase<ConversionPhase.Installing>()
        adapter.killLink()
        val back = FakeTransport()
        assertTrue(conversion.claim("1314:1520"))
        conversion.attach(back, "1314:1520")
        back.feed(frame(0xCC, "2025.10.15.1127CAY") + frame(0x19, """{"productType":"A15W"}"""))
        val failed = awaitPhase<ConversionPhase.Failed>()
        assertEquals("The adapter didn't convert", failed.title)
        assertTrue("the adapter is let go", quietAfter(back))
        conversion.dismiss()
        assertEquals(ConversionPhase.Declined, conversion.phase.value)
        assertFalse("no conversion is offered again", conversion.claim("1314:1520"))
    }

    @Test
    fun `a refusal is an adapter that never restarts, so the deadline says nothing changed and lets it go`() {
        attachAndAnswer()
        awaitPhase<ConversionPhase.Offered>()
        conversion.convert()
        awaitPhase<ConversionPhase.Installing>()
        val failed = awaitPhase<ConversionPhase.Failed>(timeoutMs = 2_000)
        assertEquals("The conversion didn't happen", failed.title)
        assertEquals("Nothing on the adapter changed. Unplug it and plug it back in to use stock CarPlay.", failed.body)
        assertTrue("the adapter is let go", quietAfter(adapter))
        assertEquals("nothing searched while it stayed", 0, searches.get())
        conversion.dismiss()
        assertEquals(ConversionPhase.Declined, conversion.phase.value)
        assertFalse("replugged: no conversion is offered again", conversion.claim("1314:1520"))
        conversion.reset() // an OCBM adapter connected
        assertTrue("until an OCBM adapter has connected", conversion.claim("1314:1520"))
    }

    @Test
    fun `an adapter that never comes back is reported, and nothing is offered again`() {
        attachAndAnswer()
        awaitPhase<ConversionPhase.Offered>()
        conversion.convert()
        awaitPhase<ConversionPhase.Installing>()
        adapter.killLink()
        assertEquals("The adapter hasn't come back", awaitPhase<ConversionPhase.Failed>(timeoutMs = 2_000).title)
        assertTrue("searched while waiting", searches.get() >= 2)
        val before = searches.get()
        conversion.dismiss()
        assertEquals(ConversionPhase.Declined, conversion.phase.value)
        assertEquals("no search after OK", before, searches.get())
        assertFalse(conversion.claim("1314:1520"))
    }

    @Test
    fun `the deadline runs from the send through the restart, not anew when the adapter leaves`() {
        attachAndAnswer()
        awaitPhase<ConversionPhase.Offered>()
        conversion.convert()
        awaitPhase<ConversionPhase.Installing>()
        Thread.sleep(500)
        val leftAt = System.currentTimeMillis()
        adapter.killLink()
        val failed = awaitPhase<ConversionPhase.Failed>(timeoutMs = 2_000)
        val after = System.currentTimeMillis() - leftAt
        assertEquals("The adapter hasn't come back", failed.title)
        assertTrue("failed $after ms after it left, inside the 700 ms from the send", after < 600)
    }

    @Test
    fun `an installer that can't be had is reported and nothing is sent`() {
        images.broken = true
        attachAndAnswer()
        awaitPhase<ConversionPhase.Offered>()
        conversion.convert()
        assertEquals("Couldn't get the installer", awaitPhase<ConversionPhase.Failed>().title)
        assertTrue(writtenFrames(waitMs = 100).none { it.first == 0x99 })
    }

    @Test
    fun `a write that fails during the send is reported`() {
        val refusing = StockConversion(images, conversionOn = true, scope = scope, searchAgain = {}, timing = timing)
        val dead = RefusingTransport()
        refusing.attach(dead, "1314:1520")
        dead.feed(frame(0xCC, "2025.10.15.1127CAY") + frame(0x19, """{"productType":"A15W"}"""))
        waitUntil(1_000) { refusing.phase.value is ConversionPhase.Offered }
        refusing.convert()
        waitUntil(1_000) { refusing.phase.value is ConversionPhase.Failed }
        assertEquals("The installer didn't finish sending", (refusing.phase.value as ConversionPhase.Failed).title)
        refusing.reset()
    }

    @Test
    fun `not now keeps quiet until the adapter leaves`() {
        attachAndAnswer()
        awaitPhase<ConversionPhase.Offered>()
        conversion.dismiss()
        assertEquals(ConversionPhase.Declined, conversion.phase.value)
        assertFalse(conversion.claim("1314:1520"))
        conversion.convert()
        assertEquals("convert does nothing once declined", ConversionPhase.Declined, conversion.phase.value)
        adapter.killLink()
        assertEquals(ConversionPhase.Idle, conversion.phase.value)
        assertTrue("plugged in again: offered again", conversion.claim("1314:1520"))
    }

    private fun attach() {
        assertTrue(conversion.claim("1314:1520"))
        conversion.attach(adapter, "1314:1520")
        assertEquals(ConversionPhase.Reading("1314:1520"), conversion.phase.value)
    }

    private fun attachAndAnswer(version: String = "2025.10.15.1127CAY") {
        attach()
        adapter.feed(frame(0xCC, version + "\u0000"))
        adapter.feed(frame(0x19, """{"uuid":"651e","productType":"A15W"}"""))
    }

    /** True once nothing more is written to [t]: its heartbeat stopped, so the link was let go. */
    private fun quietAfter(t: FakeTransport): Boolean {
        Thread.sleep(150) // the close runs on another thread
        t.written.clear()
        Thread.sleep(250) // five heartbeats' time
        return t.written.isEmpty()
    }

    private inline fun <reified T : ConversionPhase> awaitPhase(timeoutMs: Long = 1_000): T {
        waitUntil(timeoutMs) { conversion.phase.value is T }
        return conversion.phase.value as T
    }

    private fun waitUntil(
        timeoutMs: Long,
        done: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > deadline) fail("timed out; phase ${conversion.phase.value}")
            Thread.sleep(10)
        }
    }

    /** Everything written so far (after [waitMs] more), parsed back into frames as the adapter would. */
    private fun writtenFrames(waitMs: Long): List<Pair<Int, ByteArray>> {
        Thread.sleep(waitMs)
        val out = mutableListOf<Pair<Int, ByteArray>>()
        val parser = StockFrames({ type, payload -> out += type to payload }, maxPayload = Int.MAX_VALUE)
        while (true) parser.push(adapter.written.poll() ?: break)
        return out
    }

    private fun frame(
        type: Int,
        payload: String,
    ): ByteArray {
        val body = payload.toByteArray(Charsets.US_ASCII)
        val head =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x55AA55AA)
                .putInt(body.size)
                .putInt(type)
                .putInt(type.inv())
                .array()
        return head + body
    }

    private class FakeImages(
        @Volatile var image: ByteArray?,
    ) : ImageSupplier {
        @Volatile var broken = false

        override val benchFolder: String = "/bench/ocbm"

        override fun available(): Boolean = image != null

        override fun obtain(progress: (done: Long, total: Long) -> Unit): Result<ByteArray> {
            val bytes = image
            if (broken || bytes == null) return Result.failure(IOException("The installer's SHA-256 does not match. Nothing was sent."))
            progress(bytes.size.toLong(), bytes.size.toLong())
            return Result.success(bytes)
        }
    }

    /** A transport whose every write after Open fails, like a bridge whose adapter stopped taking data. */
    private class RefusingTransport : com.carlink.ocbm.RawBulkTransport {
        private val inner = FakeTransport()
        private var writes = 0

        override fun writeBulk(data: ByteArray): Boolean = ++writes == 1

        override fun setReadHandler(handler: (ByteArray, Int) -> Unit) = inner.setReadHandler(handler)

        override fun setDeadHandler(handler: () -> Unit) = inner.setDeadHandler(handler)

        override fun start() = inner.start()

        override fun stop() = inner.stop()

        fun feed(data: ByteArray) = inner.feed(data)
    }
}
