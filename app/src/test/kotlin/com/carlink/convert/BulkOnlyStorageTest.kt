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

/** USB mass storage over the bridge, against a stick in memory: the commands, the byte stream split back up, and its failures. */
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
        assertTrue("the bridge is let go", !bridge.running)
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
    fun `the rest of an answer the head unit's driver was getting is dropped before the first command`() {
        val bridge = FakeStickTransport(MemoryStick(4096))
        bridge.leftover = ByteArray(16) { 7 } + ByteArray(13) // the end of a data phase, then its status
        val lines = mutableListOf<String>()
        val storage = BulkOnlyStorage(bridge, timeoutMs = 2_000, trace = { synchronized(lines) { lines += it } })
        storage.init()
        assertEquals(4096L, storage.blocks)
        assertTrue(lines.toString(), lines.contains("dropped 29 B the stick had left from before the claim"))
        assertTrue(lines.toString(), lines.any { it.startsWith("CBW tag 1 op 0x12, 36 B in: sent") })
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
    fun `a status for another command ends the session`() {
        val bridge = FakeStickTransport(MemoryStick(4096))
        bridge.badTag = true
        try {
            BulkOnlyStorage(bridge, timeoutMs = 2_000).init()
            fail("out of step")
        } catch (e: IOException) {
            assertEquals("the stick's answer is out of step", e.message)
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

    @Test
    fun `the inbox hands back exact lengths across pieces, and fails when the stick goes or goes quiet`() {
        val inbox = ByteInbox()
        inbox.put(byteArrayOf(1, 2, 3), 3)
        inbox.put(byteArrayOf(4, 5, 9, 9), 2)
        assertEquals(listOf<Byte>(1, 2), inbox.read(2, 100).toList())
        assertEquals(listOf<Byte>(3, 4, 5), inbox.read(3, 100).toList())
        try {
            inbox.read(1, 50)
            fail("nothing came")
        } catch (e: IOException) {
            assertEquals("the stick did not answer within 0 s", e.message)
        }
        inbox.close()
        try {
            inbox.read(1, 1_000)
            fail("closed")
        } catch (e: IOException) {
            assertEquals("the stick went away", e.message)
        }
    }
}
