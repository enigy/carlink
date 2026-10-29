package com.carlink.convert

import me.jahnen.libaums.core.fs.FileSystem
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
import kotlin.random.Random

/** The installer onto a FAT32 stick through libaums, on sticks in memory: what goes on, and each way it is refused. */
@RunWith(RobolectricTestRunner::class) // libaums logs through android.util.Log
class StickFilesTest {
    private val image = Random(7).nextBytes(300_000)
    private val sha = InstallerImage.sha256Hex(image)

    @Test
    fun `the installer goes onto an empty stick whole, under the name the adapter installs from`() {
        val stick = MemoryStick.superfloppy(64)
        val fs = fat32(stick)
        val contents = StickFiles.inspect(fs)
        assertNull(contents.report)
        assertFalse(contents.hasImage)
        assertFalse(StickFiles.tooFull(contents, image.size))
        val progress = mutableListOf<Long>()
        StickFiles.write(fs, image, sha, retry = false) { done, total ->
            assertEquals(image.size.toLong(), total)
            progress += done
        }
        assertEquals(image.size.toLong(), progress.last())
        val again = fat32(stick) // read back from the blocks, as the adapter will
        assertEquals(listOf(StickFiles.IMAGE), names(again))
        assertEquals(sha, InstallerImage.sha256Hex(read(again, StickFiles.IMAGE)))
        assertTrue(StickFiles.imageWaiting(again))
    }

    @Test
    fun `what the head unit puts on a stick it mounts is neither a report nor an image, and stays`() {
        val stick = MemoryStick.partitioned(64)
        val folders = listOf("LOST.DIR", "Movies", "Music", "Pictures") // truck test, 29 Oct
        folders.forEach { fat32(stick).rootDirectory.createDirectory(it) }
        val contents = StickFiles.inspect(fat32(stick))
        assertNull(contents.report)
        assertFalse(contents.hasImage)
        assertEquals(0L, contents.reclaimableBytes)
        assertFalse(StickFiles.tooFull(contents, image.size))
        StickFiles.write(fat32(stick), image, sha, retry = false) { _, _ -> }
        assertEquals((folders + StickFiles.IMAGE).sorted(), names(fat32(stick)).sorted())
    }

    @Test
    fun `a stick with a partition table is written in its FAT32 partition`() {
        val stick = MemoryStick.partitioned(64)
        StickFiles.write(fat32(stick), image, sha, retry = false) { _, _ -> }
        assertEquals(sha, InstallerImage.sha256Hex(read(fat32(stick), StickFiles.IMAGE)))
        assertEquals("the partition table is untouched", 0x0C, stick.block(0)[0x1BE + 4].toInt())
    }

    @Test
    fun `try again puts an empty OCBM_RETRY beside the image`() {
        val stick = MemoryStick.superfloppy(64)
        StickFiles.write(fat32(stick), image, sha, retry = true) { _, _ -> }
        val fs = fat32(stick)
        assertEquals(listOf(StickFiles.IMAGE, StickFiles.RETRY), names(fs).sorted())
        assertEquals(0L, fs.rootDirectory.search(StickFiles.RETRY)?.length)
    }

    @Test
    fun `an image already on the stick counts as room, and is replaced`() {
        val stick = MemoryStick.superfloppy(64)
        val old = Random(1).nextBytes(500_000)
        StickFiles.write(fat32(stick), old, InstallerImage.sha256Hex(old), retry = false) { _, _ -> }
        val contents = StickFiles.inspect(fat32(stick))
        assertTrue(contents.hasImage)
        assertTrue("reclaimable ${contents.reclaimableBytes}", contents.reclaimableBytes >= old.size)
        StickFiles.write(fat32(stick), image, sha, retry = false) { _, _ -> }
        val fs = fat32(stick)
        assertEquals(listOf(StickFiles.IMAGE), names(fs))
        assertEquals(sha, InstallerImage.sha256Hex(read(fs, StickFiles.IMAGE)))
    }

    @Test
    fun `a stick that gives back something else keeps no new image, and the old one stays`() {
        val stick = MemoryStick.superfloppy(64)
        val old = Random(1).nextBytes(200_000)
        StickFiles.write(fat32(stick), old, InstallerImage.sha256Hex(old), retry = false) { _, _ -> }
        val head = image.copyOfRange(0, 16)
        stick.corrupt = { _, block -> if (block.copyOfRange(0, 16).contentEquals(head)) block[100] = (block[100].toInt() xor 1).toByte() }
        try {
            StickFiles.write(fat32(stick), image, sha, retry = true) { _, _ -> }
            fail("a bad read-back must fail the write")
        } catch (e: IOException) {
            assertEquals("what the stick gave back does not match the installer", e.message)
        }
        stick.corrupt = null
        val fs = fat32(stick)
        assertEquals("no .part, no OCBM_RETRY", listOf(StickFiles.IMAGE), names(fs))
        assertEquals("the image before is still whole", InstallerImage.sha256Hex(old), InstallerImage.sha256Hex(read(fs, StickFiles.IMAGE)))
    }

    @Test
    fun `too little room for the installer and once_sh's 40 MB backup is refused`() {
        assertEquals("once.sh's STICK_NEED_KB, 40960 KB", 40960L * 1024, StickFiles.BACKUP_ROOM)
        val small = StickFiles.inspect(fat32(MemoryStick.superfloppy(40)))
        assertNotNull(small.freeBytes)
        assertTrue("40 MiB holds less than 40 MiB plus the image", StickFiles.tooFull(small, image.size))
        assertFalse(StickFiles.tooFull(StickFiles.inspect(fat32(MemoryStick.superfloppy(48))), image.size))
        val mib = 1024L * 1024
        assertTrue(StickFiles.tooFull(StickContents(null, false, 40 * mib, 0), 1))
        assertFalse("a replaced image frees its room", StickFiles.tooFull(StickContents(null, true, 40 * mib, 2 * mib), mib.toInt()))
        assertFalse("free space the stick doesn't say is left to once.sh", StickFiles.tooFull(StickContents(null, false, null, 0), mib.toInt()))
    }

    @Test
    fun `anything but FAT32 is refused, and says what it is`() {
        assertEquals("no partition table or file system", notFat32(MemoryStick(4096)))
        assertEquals("exFAT or NTFS", notFat32(withSector0(MemoryStick.mbr(0x07, 2048, 2048))))
        assertEquals("FAT16", notFat32(withSector0(MemoryStick.mbr(0x06, 2048, 2048))))
        assertEquals("a GPT partition table", notFat32(withSector0(MemoryStick.mbr(0xEE, 1, 4095))))
        assertEquals("an empty partition table", notFat32(withSector0(MemoryStick.mbr(0x00, 0, 0))))
        assertEquals("exFAT", notFat32(withSector0(boot(oem = "EXFAT   "))))
        assertEquals("NTFS", notFat32(withSector0(boot(oem = "NTFS    "))))
        assertEquals("FAT16", notFat32(withSector0(boot(oem = "mkfs.fat", at0x36 = "FAT16   "))))
        assertEquals(
            "a FAT32 partition that holds no FAT32 file system",
            notFat32(withSector0(MemoryStick.mbr(0x0C, 2048, 2048))),
        )
    }

    @Test
    fun `the report on the stick is read before anything is written`() {
        val stick = MemoryStick.superfloppy(64)
        val fs = fat32(stick)
        put(fs, StickFiles.RESULT, Reports.undone.toByteArray())
        put(fs, "${StickFiles.IMAGE}.refused", ByteArray(1000))
        val contents = StickFiles.inspect(fat32(stick))
        assertEquals("REFUSED", contents.report?.headline)
        assertTrue(contents.report?.undone == true)
        assertFalse("the refused image is not the installer waiting", contents.hasImage)
    }

    @Test
    fun `reports parse as the installer writes them`() {
        val undone = reportOf(Reports.undone)
        assertEquals("REFUSED", undone.headline)
        assertEquals("1.0.0", undone.installer)
        assertTrue(undone.undone)
        assertFalse(undone.installed)
        assertEquals(2, undone.paragraphs.size)
        assertTrue(undone.paragraphs[0].startsWith("This installer (1.0.0) was installed and undone on this adapter before."))
        assertEquals(
            "Nothing on the adapter was changed. Remove the stick, then unplug the adapter and plug it back in: it starts stock CarPlay as before.",
            undone.paragraphs[1],
        )
        assertTrue("the log is not the message", undone.paragraphs.none { "route: stick" in it })

        val firmware = reportOf(Reports.wrongFirmware)
        assertEquals("REFUSED", firmware.headline)
        assertFalse("another refusal needs no OCBM_RETRY", firmware.undone)

        val installed = reportOf(Reports.installed)
        assertTrue(installed.installed)
        assertFalse(installed.undone)
        assertEquals("INSTALLED (trial)", installed.headline)
        assertTrue(installed.paragraphs[1].startsWith("A full flash backup is in OCBM_BACKUP/651e on this stick. It holds the adapter's Wi-Fi password"))

        val back = reportOf(Reports.backToStock)
        assertEquals(StickReport.BACK_TO_STOCK, back.headline)
        assertTrue("back to stock asks for OCBM_RETRY", back.undone)
        assertTrue("the self-test section is not the message", back.paragraphs.none { "self-test: FAILED" in it })

        assertEquals("line endings from a computer don't matter", undone, reportOf(Reports.undone.replace("\n", "\r\n")))
        assertNull(StickReport.parse(""))
        assertNull(StickReport.parse("hello\nworld"))
    }

    private fun reportOf(text: String): StickReport = StickReport.parse(text) ?: throw AssertionError("not parsed: $text")

    private fun fat32(stick: MemoryStick): FileSystem =
        when (val v = StickVolume.open(stick)) {
            is StickVolume.Fat32 -> v.fs
            is StickVolume.NotFat32 -> throw AssertionError("not FAT32: ${v.found}")
        }

    private fun notFat32(stick: MemoryStick): String =
        when (val v = StickVolume.open(stick)) {
            is StickVolume.Fat32 -> throw AssertionError("read as FAT32")
            is StickVolume.NotFat32 -> v.found
        }

    private fun withSector0(s0: ByteArray): MemoryStick = MemoryStick(4096).also { it.putBlock(0, s0) }

    /** A whole-stick boot sector that is not FAT32's. */
    private fun boot(
        oem: String,
        at0x36: String = "",
    ): ByteArray {
        val b = ByteArray(MemoryStick.BLOCK)
        b[0] = 0xEB.toByte()
        b[1] = 0x3C
        b[2] = 0x90.toByte()
        oem.toByteArray().copyInto(b, 3)
        at0x36.toByteArray().copyInto(b, 0x36)
        b[510] = 0x55
        b[511] = 0xAA.toByte()
        return b
    }

    private fun names(fs: FileSystem): List<String> = fs.rootDirectory.listFiles().map { it.name }

    private fun read(
        fs: FileSystem,
        name: String,
    ): ByteArray {
        val file = fs.rootDirectory.search(name) ?: throw AssertionError("$name is not on the stick")
        val out = ByteArray(file.length.toInt())
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
}
