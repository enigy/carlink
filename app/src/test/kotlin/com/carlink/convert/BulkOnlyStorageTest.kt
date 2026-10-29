package com.carlink.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** USB mass storage over the bridge's on-request session, against a strict stick in memory: each phase, and each way it fails. */
@RunWith(RobolectricTestRunner::class)
class BulkOnlyStorageTest {
    @Test
    fun `the installer reaches the stick through Bulk-Only Transport and libaums`() {
        val stick = MemoryStick.partitioned(64)
        val bridge = FakeStickTransport(stick)
        val storage = BulkOnlyStorage(bridge, timeoutMs = 2_000)
        storage.init()
        assertEquals(512, storage.blockSize)
        assertEquals(stick.blocks, storage.blocks)
        val fs = (StickVolume.open(storage) as StickVolume.Fat32).fs
        val image = Random(3).nextBytes(150_000)
        StickFiles.write(fs, image, InstallerImage.sha256Hex(image), retry = false) { _, _ -> }
        storage.sync()
        storage.release()
        val direct = (StickVolume.open(stick) as StickVolume.Fat32).fs
        val file = direct.rootDirectory.search(StickFiles.IMAGE) ?: throw AssertionError("no image on the stick")
        val back = ByteArray(file.length.toInt()).also { file.read(0, ByteBuffer.wrap(it)) }
        assertEquals(InstallerImage.sha256Hex(image), InstallerImage.sha256Hex(back))
        val ops = synchronized(bridge.commands) { bridge.commands.toSet() }
        assertTrue("INQUIRY, TEST UNIT READY, READ CAPACITY, READ, WRITE, SYNCHRONIZE CACHE: $ops", ops.containsAll(listOf(0x12, 0x00, 0x25, 0x28, 0x2A, 0x35)))
        assertTrue("never a stall, so never a recovery", bridge.clears.isEmpty() && bridge.resets == 0)
        assertTrue("the stick is let go", !bridge.open)
    }

    @Test
    fun `only what a command block announced is read, and the status in exactly 13 bytes`() {
        val bridge = FakeStickTransport(MemoryStick(4096))
        val storage = BulkOnlyStorage(bridge, timeoutMs = 2_000)
        storage.init()
        bridge.readSizes.clear()
        storage.read(0, ByteBuffer.allocate(512))
        assertEquals(listOf(512, 13), bridge.readSizes)
        assertTrue("no read the stick wasn't expecting: it would have stalled", !bridge.halted && bridge.clears.isEmpty())
    }

    @Test
    fun `whatever the head unit's driver left mid-answer is cleared by reset recovery, and the first command asked again`() {
        val bridge = FakeStickTransport(MemoryStick(4096))
        bridge.midAnswer(20)
        val lines = mutableListOf<String>()
        val storage = BulkOnlyStorage(bridge, timeoutMs = 2_000, trace = { lines += it })
        storage.init()
        assertEquals(4096L, storage.blocks)
        assertEquals(1, bridge.resets)
        assertTrue(lines.toString(), lines.contains("reset recovery: done"))
        assertEquals("INQUIRY once it was heard, then on", listOf(0x12, 0x00, 0x25), bridge.commands)
    }

    @Test
    fun `a stick that isn't ready at first is asked again, with REQUEST SENSE between`() {
        val bridge = FakeStickTransport(MemoryStick(4096))
        var notReady = 2
        bridge.onCommand = { op -> if (op == 0x00 && notReady-- > 0) 1 else null }
        val storage = BulkOnlyStorage(bridge, timeoutMs = 2_000)
        storage.init()
        assertEquals(4096L, storage.blocks)
        assertEquals(listOf(0x12, 0x00, 0x03, 0x00, 0x03, 0x00, 0x25), bridge.commands)
    }

    @Test
    fun `a failed command says which, with its sense`() {
        val bridge = FakeStickTransport(MemoryStick(4096))
        val storage = BulkOnlyStorage(bridge, timeoutMs = 2_000)
        storage.init()
        bridge.onCommand = { op -> if (op == 0x2A) 1 else null }
        try {
            storage.write(0, ByteBuffer.allocate(512))
            fail("the write failed")
        } catch (e: IOException) {
            assertTrue(e.message, e.message.orEmpty().startsWith("SCSI 0x2a failed (status 1, sense "))
        }
    }

    @Test
    fun `a stalled data phase clears the halt and still reads the status`() {
        val bridge = FakeStickTransport(MemoryStick(4096))
        val storage = BulkOnlyStorage(bridge, timeoutMs = 2_000)
        storage.init()
        bridge.stallDataIn = true
        try {
            storage.read(0, ByteBuffer.allocate(512))
            fail("the read failed")
        } catch (e: IOException) {
            assertTrue(e.message, e.message.orEmpty().startsWith("SCSI 0x28 failed (status 1"))
        }
        assertEquals("the IN halt cleared", listOf(true), bridge.clears)
        assertEquals("no reset needed", 0, bridge.resets)
        val again = ByteBuffer.allocate(512)
        storage.read(0, again)
        assertEquals("in step again", 512, again.position())
    }

    @Test
    fun `a status for another command means reset recovery, and the session says so`() {
        val bridge = FakeStickTransport(MemoryStick(4096))
        val storage = BulkOnlyStorage(bridge, timeoutMs = 2_000)
        storage.init()
        bridge.badTag = true
        try {
            storage.read(0, ByteBuffer.allocate(512))
            fail("out of step")
        } catch (e: IOException) {
            assertEquals("the stick's answer is out of step", e.message)
        }
        assertEquals(1, bridge.resets)
    }

    @Test
    fun `a stick gone mid-command gives no status`() {
        val bridge = FakeStickTransport(MemoryStick(4096))
        val storage = BulkOnlyStorage(bridge, timeoutMs = 2_000)
        storage.init()
        bridge.onCommand = { _ ->
            bridge.open = false
            null
        }
        try {
            storage.read(0, ByteBuffer.allocate(512))
            fail("gone")
        } catch (e: IOException) {
            assertEquals("the stick gave no status", e.message)
        }
    }

    @Test
    fun `reads and writes go in commands of at most 128 blocks`() {
        val stick = MemoryStick(4096)
        val bridge = FakeStickTransport(stick)
        val storage = BulkOnlyStorage(bridge, timeoutMs = 2_000)
        storage.init()
        val data = Random(5).nextBytes(300 * 512)
        bridge.commands.clear()
        storage.write(10, ByteBuffer.wrap(data))
        assertEquals(listOf(0x2A, 0x2A, 0x2A), bridge.commands)
        val back = ByteBuffer.allocate(data.size)
        storage.read(10, back)
        assertTrue(back.array().contentEquals(data))
        assertTrue(stick.block(10).contentEquals(data.copyOfRange(0, 512)))
    }

    @Test
    fun `the command block is Bulk-Only's 31 bytes`() {
        val read10 = BulkOnlyStorage.rw(BulkOnlyStorage.READ_10, 0x01020304, 128)
        assertEquals(listOf(0x28, 0, 1, 2, 3, 4, 0, 0, 128, 0), read10.map { it.toInt() and 0xFF })
        val cbw = ByteBuffer.wrap(BulkOnlyStorage.cbw(7, 65536, dataIn = true, cb = read10)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(31, cbw.capacity())
        assertEquals(0x43425355, cbw.getInt(0))
        assertEquals(7, cbw.getInt(4))
        assertEquals(65536, cbw.getInt(8))
        assertEquals(0x80, cbw.get(12).toInt() and 0xFF)
        assertEquals(0, cbw.get(13).toInt())
        assertEquals(10, cbw.get(14).toInt())
        assertEquals(0x28, cbw.get(15).toInt())
        assertEquals(0, BulkOnlyStorage.cbw(8, 512, dataIn = false, cb = read10)[12].toInt())
    }
}
