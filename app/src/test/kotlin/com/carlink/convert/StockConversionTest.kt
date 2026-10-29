package com.carlink.convert

import com.carlink.ocbm.FakeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.jahnen.libaums.core.fs.FileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Converting a stock adapter, against a fake adapter and sticks in memory: what is offered, what goes on the stick, and each way it ends. */
@RunWith(RobolectricTestRunner::class)
class StockConversionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val images = FakeImages(Random(11).nextBytes(120_000))
    private val usb = FakeUsb()
    private val timing = StockConversion.Timing(readMs = 400, heartbeatMs = 50, deadlineMs = 700, searchMs = 200, pollMs = 20, openDelayMs = 30)
    private val conversion = StockConversion(images, conversionOn = true, scope = scope, usb = usb, timing = timing, benchStick = false)
    private val adapter = FakeTransport()

    // The sticks on USB by device name, and the bridge sessions the manager opened for them.
    private val sticks = ConcurrentHashMap<String, MemoryStick>()
    private val bridges = ConcurrentHashMap<String, FakeStickTransport>()

    init {
        usb.onOpen = ::openForTest
    }

    @After
    fun tearDown() {
        conversion.reset()
        scope.cancel()
    }

    @Test
    fun `with the switch off a stock adapter is only recognised, never opened`() {
        val off = StockConversion(images, conversionOn = false, scope = scope, usb = usb, timing = timing)
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
    fun `an installer that can't be had is reported, and no stick is asked for`() {
        images.broken = true
        attachAndAnswer()
        awaitPhase<ConversionPhase.Offered>()
        conversion.convert()
        assertEquals("Couldn't get the installer", awaitPhase<ConversionPhase.Failed>().title)
        usb.devices[STICK] = ADAPTER_STICK
        assertFalse(conversion.wantsStick(0x1d6b, 0xa4a5, STICK))
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

    @Test
    fun `convert gets the installer, then asks for the stick, holding the adapter meanwhile`() {
        toWaitingForStick()
        Thread.sleep(100)
        assertEquals("the adapter's connect path never looks for the stick", 0, usb.searches.get())
        assertFalse("no other stock adapter is opened", conversion.claim("1314:1520"))
        assertFalse("the adapter's heartbeat goes on", quietAfter(adapter))
    }

    @Test
    fun `only a shared stick that appears after Convert is taken`() {
        attachAndAnswer()
        awaitPhase<ConversionPhase.Offered>()
        usb.devices[EARLIER] = ADAPTER_STICK
        assertFalse("no write is offered to a shared stick outside the conversion", conversion.wantsStick(0x1d6b, 0xa4a5, EARLIER))
        conversion.convert()
        awaitPhase<ConversionPhase.WaitingForStick>()
        assertFalse("on USB before Convert: someone else's", conversion.wantsStick(0x1d6b, 0xa4a5, EARLIER))
        conversion.stickAttached(0x1d6b, 0xa4a5, EARLIER)
        Thread.sleep(3 * timing.pollMs + 50) // and the conversion's own look passes it over too
        assertTrue("nothing opened: ${usb.opened}", usb.opened.isEmpty())
        assertEquals(ConversionPhase.WaitingForStick, conversion.phase.value)
        assertTrue(conversion.wantsStick(0x1d6b, 0xa4a5, STICK))
        assertFalse("not a shared stick", conversion.wantsStick(0x0781, 0x5567, STICK))
        assertFalse("the emulator's stick only in debug builds", conversion.wantsStick(0x46f4, 0x0001, STICK))
    }

    @Test
    fun `debug builds take the emulator's stick, even one plugged in before Convert`() {
        val bench = StockConversion(images, conversionOn = true, scope = scope, usb = usb, timing = timing, benchStick = true)
        assertTrue(bench.claim("1314:1520"))
        bench.attach(adapter, "1314:1520")
        adapter.feed(frame(0xCC, "2025.10.15.1127CAY") + frame(0x19, """{"productType":"A15W"}"""))
        waitUntil(1_000) { bench.phase.value is ConversionPhase.Offered }
        usb.devices[EARLIER] = 0x46f4 to 0x0001
        assertFalse(bench.wantsStick(0x46f4, 0x0001, EARLIER))
        bench.convert()
        waitUntil(1_000) { bench.phase.value == ConversionPhase.WaitingForStick }
        assertTrue(bench.wantsStick(0x46f4, 0x0001, EARLIER))
        waitUntil(1_000) { EARLIER in usb.opened } // found by looking: no attach broadcast for a stick plugged in first
        assertEquals(ConversionPhase.ReadingStick, bench.phase.value)
        bench.reset()
    }

    @Test
    fun `the adapter leaving USB as the stick goes in keeps the conversion waiting`() {
        toWaitingForStick()
        adapter.killLink()
        assertEquals(ConversionPhase.WaitingForStick, conversion.phase.value)
    }

    @Test
    fun `an empty stick gets the installer at once, then the driver is asked to replug the adapter`() {
        toWaitingForStick()
        adapter.killLink()
        val stick = MemoryStick.partitioned(64)
        plugStick(stick, STICK)
        awaitPhase<ConversionPhase.ReplugAdapter>(timeoutMs = 10_000)
        assertFalse("the stick is let go", bridges.getValue(STICK).open)
        val fs = fat32(stick)
        assertEquals(listOf(StickFiles.IMAGE), names(fs))
        assertEquals(InstallerImage.sha256Hex(images.image!!), InstallerImage.sha256Hex(read(fs, StickFiles.IMAGE)))
        assertFalse("taken once", conversion.wantsStick(0x1d6b, 0xa4a5, STICK))
        usb.devices.remove(STICK) // the adapter unplugged
        assertNull(awaitPhase<ConversionPhase.InstallingFromStick>().hint)
        conversion.reset() // the manager found it back as 1314:2d00
        assertEquals(ConversionPhase.Idle, conversion.phase.value)
    }

    @Test
    fun `a stick missed by its attach broadcast is found by looking`() {
        toWaitingForStick()
        adapter.killLink()
        sticks[STICK] = MemoryStick.superfloppy(64)
        usb.devices[STICK] = ADAPTER_STICK
        awaitPhase<ConversionPhase.ReplugAdapter>(timeoutMs = 10_000)
        assertEquals(listOf(STICK), usb.opened)
    }

    @Test
    fun `the stock adapter's link is let go before the stick is opened`() {
        toWaitingForStick()
        val writesWhileOpening = AtomicInteger(-1)
        usb.onOpen = { name ->
            adapter.written.clear()
            Thread.sleep(150) // three heartbeats' time, were the link still up
            writesWhileOpening.set(adapter.written.size)
            openForTest(name)
        }
        plugStick(MemoryStick.superfloppy(64), STICK)
        awaitPhase<ConversionPhase.ReplugAdapter>(timeoutMs = 10_000)
        assertEquals("nothing but the stick's storage uses the bridge", 0, writesWhileOpening.get())
    }

    @Test
    fun `a stick the head unit mounted, with LOST_DIR and its media folders, is written at once`() {
        toWaitingForStick()
        val stick = MemoryStick.superfloppy(64)
        val folders = listOf("LOST.DIR", "Movies", "Music", "Pictures")
        folders.forEach { fat32(stick).rootDirectory.createDirectory(it) }
        plugStick(stick, STICK)
        awaitPhase<ConversionPhase.ReplugAdapter>(timeoutMs = 10_000)
        assertEquals((folders + StickFiles.IMAGE).sorted(), names(fat32(stick)).sorted())
    }

    @Test
    fun `the conversion holds the bridge only while the stick is open`() {
        toWaitingForStick()
        assertFalse(conversion.holdsStick)
        val stick = MemoryStick.superfloppy(64)
        put(fat32(stick), StickFiles.RESULT, Reports.wrongFirmware.toByteArray())
        plugStick(stick, STICK)
        awaitPhase<ConversionPhase.StickReady>(timeoutMs = 5_000)
        assertTrue(conversion.holdsStick)
        conversion.dismiss()
        assertFalse(conversion.holdsStick)
        assertFalse("the stick is let go", bridges.getValue(STICK).open)
    }

    @Test
    fun `the installer's last report is shown first, and Try again adds OCBM_RETRY`() {
        toWaitingForStick()
        val stick = MemoryStick.superfloppy(64)
        put(fat32(stick), StickFiles.RESULT, Reports.undone.toByteArray())
        plugStick(stick, STICK)
        val ready = awaitPhase<ConversionPhase.StickReady>(timeoutMs = 5_000)
        assertEquals("REFUSED", ready.report?.headline)
        assertTrue(ready.retry)
        assertFalse(ready.returned)
        assertFalse(ready.hasImage)
        assertEquals("nothing written before the driver says so", listOf(StickFiles.RESULT), names(fat32(stick)))
        conversion.writeStick()
        awaitPhase<ConversionPhase.ReplugAdapter>(timeoutMs = 10_000)
        assertEquals(listOf(StickFiles.IMAGE, StickFiles.RESULT, StickFiles.RETRY), names(fat32(stick)).sorted())
    }

    @Test
    fun `an image already on the stick is replaced only once the driver confirms`() {
        toWaitingForStick()
        val stick = MemoryStick.superfloppy(64)
        put(fat32(stick), StickFiles.IMAGE, ByteArray(5_000) { 1 })
        plugStick(stick, STICK)
        val ready = awaitPhase<ConversionPhase.StickReady>(timeoutMs = 5_000)
        assertTrue(ready.hasImage)
        assertNull(ready.report)
        assertFalse(ready.retry)
        assertEquals(5_000, read(fat32(stick), StickFiles.IMAGE).size)
        conversion.writeStick()
        awaitPhase<ConversionPhase.ReplugAdapter>(timeoutMs = 10_000)
        val fs = fat32(stick)
        assertEquals(listOf(StickFiles.IMAGE), names(fs))
        assertEquals(InstallerImage.sha256Hex(images.image!!), InstallerImage.sha256Hex(read(fs, StickFiles.IMAGE)))
    }

    @Test
    fun `a stick that isn't FAT32 is turned away, and read again when it is put back`() {
        toWaitingForStick()
        plugStick(MemoryStick(16_384), STICK)
        val problem = awaitPhase<ConversionPhase.StickProblem>(timeoutMs = 5_000)
        assertEquals("The stick isn't FAT32", problem.title)
        assertTrue(problem.body, problem.body.contains("Format it as FAT32 on a computer"))
        assertFalse("the same stick is not read again", conversion.wantsStick(0x1d6b, 0xa4a5, STICK))
        plugStick(MemoryStick.superfloppy(64), AGAIN)
        awaitPhase<ConversionPhase.ReplugAdapter>(timeoutMs = 10_000)
    }

    @Test
    fun `a stick without room for the installer and the backup is turned away`() {
        toWaitingForStick()
        val stick = MemoryStick.superfloppy(40)
        plugStick(stick, STICK)
        val problem = awaitPhase<ConversionPhase.StickProblem>(timeoutMs = 5_000)
        assertEquals("The stick is too full", problem.title)
        assertTrue(names(fat32(stick)).isEmpty())
    }

    @Test
    fun `the adapter back sharing the stick shows the installer's report, and offers nothing more`() {
        val back = MemoryStick.superfloppy(64) // as the installer leaves it, ready before the deadline runs
        put(fat32(back), StickFiles.RESULT, Reports.wrongFirmware.toByteArray())
        put(fat32(back), "${StickFiles.IMAGE}.refused", ByteArray(100))
        toInstalling()
        plugStick(back, AGAIN)
        val ready = awaitPhase<ConversionPhase.StickReady>(timeoutMs = 5_000)
        assertTrue(ready.returned)
        assertFalse(ready.retry)
        val said = ready.report?.paragraphs.orEmpty()
        assertTrue(said.toString(), said.first().startsWith("This adapter runs firmware 2025.02.25.1521."))
        conversion.writeStick()
        assertEquals("no retry offered for this refusal", ready, conversion.phase.value)
        conversion.dismiss()
        assertEquals(ConversionPhase.Declined, conversion.phase.value)
        assertFalse("no conversion is offered again", conversion.claim("1314:1520"))
    }

    @Test
    fun `back sharing the stick with the installer not started, the driver is told to put the stick in again`() {
        val stick = toInstalling()
        plugStick(stick, AGAIN)
        val hint = awaitPhase<ConversionPhase.InstallingFromStick>(timeoutMs = 5_000) { it.hint != null }.hint
        assertTrue(hint.orEmpty(), hint.orEmpty().contains("Take the stick out of the adapter and put it back in"))
    }

    @Test
    fun `an adapter that never comes back is reported at the deadline, and nothing is offered again`() {
        toInstalling()
        val before = usb.searches.get()
        val failed = awaitPhase<ConversionPhase.Failed>(timeoutMs = 2_000)
        assertEquals("The adapter hasn't come back", failed.title)
        assertTrue("searched while waiting", usb.searches.get() - before >= 2)
        conversion.dismiss()
        assertEquals(ConversionPhase.Declined, conversion.phase.value)
        assertFalse(conversion.claim("1314:1520"))
    }

    @Test
    fun `a stock adapter while it installs is not opened, and the deadline says it didn't convert`() {
        toInstalling()
        assertFalse(conversion.claim("1314:1520"))
        assertEquals("The adapter didn't convert", awaitPhase<ConversionPhase.Failed>(timeoutMs = 2_000).title)
    }

    @Test
    fun `cancel while waiting for the stick, the adapter gone, looks for it again`() {
        toWaitingForStick()
        adapter.killLink()
        val before = usb.searches.get()
        conversion.dismiss()
        assertEquals(ConversionPhase.Idle, conversion.phase.value)
        assertEquals(before + 1, usb.searches.get())
        assertFalse(conversion.wantsStick(0x1d6b, 0xa4a5, STICK))
        assertTrue("its stick out, it is offered again", conversion.claim("1314:1520"))
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

    private fun toWaitingForStick() {
        attachAndAnswer()
        awaitPhase<ConversionPhase.Offered>()
        conversion.convert()
        awaitPhase<ConversionPhase.WaitingForStick>()
    }

    /** Through the write and the replug: the adapter is away installing. Returns the stick it took. */
    private fun toInstalling(): MemoryStick {
        toWaitingForStick()
        adapter.killLink()
        val stick = MemoryStick.superfloppy(64)
        plugStick(stick, STICK)
        awaitPhase<ConversionPhase.ReplugAdapter>(timeoutMs = 10_000)
        usb.devices.remove(STICK)
        awaitPhase<ConversionPhase.InstallingFromStick>()
        return stick
    }

    /** The stick shows up on USB as [name], with its attach broadcast: the conversion takes it and the manager opens it. */
    private fun plugStick(
        stick: MemoryStick,
        name: String,
    ) {
        sticks[name] = stick
        usb.devices[name] = ADAPTER_STICK
        assertTrue("wanted: $name in ${conversion.phase.value}", conversion.wantsStick(0x1d6b, 0xa4a5, name))
        conversion.stickAttached(0x1d6b, 0xa4a5, name)
    }

    /** The manager's side of [ConversionUsb.openStick]: the stick's bridge session, handed back. */
    private fun openForTest(name: String) {
        val bridge = sticks[name]?.let(::FakeStickTransport)
        bridge?.let { bridges[name] = it }
        conversion.stickOpened(bridge)
    }

    /** True once nothing more is written to [t]: its heartbeat stopped, so the link was let go. */
    private fun quietAfter(t: FakeTransport): Boolean {
        Thread.sleep(150) // the close runs on another thread
        t.written.clear()
        Thread.sleep(250) // five heartbeats' time
        return t.written.isEmpty()
    }

    private inline fun <reified T : ConversionPhase> awaitPhase(
        timeoutMs: Long = 1_000,
        crossinline where: (T) -> Boolean = { true },
    ): T {
        waitUntil(timeoutMs) { (conversion.phase.value as? T)?.let(where) == true }
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

    private fun fat32(stick: MemoryStick): FileSystem = (StickVolume.open(stick) as StickVolume.Fat32).fs

    private fun names(fs: FileSystem): List<String> = fs.rootDirectory.listFiles().map { it.name }

    private fun read(
        fs: FileSystem,
        name: String,
    ): ByteArray {
        val file = fs.rootDirectory.search(name)
        assertNotNull("$name is on the stick", file)
        val out = ByteArray(file!!.length.toInt())
        file.read(0, ByteBuffer.wrap(out))
        return out
    }

    private fun put(
        fs: FileSystem,
        name: String,
        bytes: ByteArray,
    ) {
        val file = fs.rootDirectory.createFile(name)
        file.write(0, ByteBuffer.wrap(bytes))
        file.close()
    }

    private class FakeImages(
        @Volatile var image: ByteArray?,
    ) : ImageSupplier {
        @Volatile var broken = false

        override val benchFolder: String = "/bench/ocbm"

        override fun available(): Boolean = image != null

        override fun obtain(progress: (done: Long, total: Long) -> Unit): Result<ByteArray> {
            val bytes = image
            if (broken || bytes == null) return Result.failure(IOException("The installer's SHA-256 does not match. Nothing was written."))
            progress(bytes.size.toLong(), bytes.size.toLong())
            return Result.success(bytes)
        }
    }

    /** USB as the manager sees it: device names and their ids, and the searches asked of it. */
    private class FakeUsb : ConversionUsb {
        val devices = ConcurrentHashMap<String, Pair<Int, Int>>()
        val searches = AtomicInteger()
        val opened = CopyOnWriteArrayList<String>()

        @Volatile var onOpen: ((String) -> Unit)? = null

        override fun attached(accepts: (vendorId: Int, productId: Int) -> Boolean): Set<String> =
            devices.filterValues { (vendor, product) -> accepts(vendor, product) }.keys.toSet()

        override fun searchAgain() {
            searches.incrementAndGet()
        }

        override fun openStick(deviceName: String) {
            opened += deviceName
            onOpen?.invoke(deviceName)
        }
    }

    private companion object {
        const val EARLIER = "/dev/bus/usb/001/004"
        const val STICK = "/dev/bus/usb/001/007"
        const val AGAIN = "/dev/bus/usb/001/009"
        val ADAPTER_STICK = 0x1d6b to 0xa4a5
    }
}
