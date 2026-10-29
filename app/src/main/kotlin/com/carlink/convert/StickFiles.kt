package com.carlink.convert

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.ByteBlockDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.fat32.Fat32FileSystem
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The report the installer leaves on the stick, OCBM_RESULT.txt: "OCBM install: <HEADLINE>", then
 * "installer: <version>", then the message for the driver up to the first "--- " section (the log,
 * the self-test, "--- before that"). once.sh, finish.sh and ocbm_revert.sh in enigy/ocbm write it.
 */
data class StickReport(
    val headline: String,
    val installer: String?,
    val paragraphs: List<String>,
) {
    /** The report says this version was installed and undone: a new try needs OCBM_RETRY on the stick. */
    val undone: Boolean get() = headline == BACK_TO_STOCK || paragraphs.any { UNDONE in it }

    val installed: Boolean get() = headline.startsWith(INSTALLED)

    companion object {
        const val BACK_TO_STOCK = "BACK TO STOCK"
        const val INSTALLED = "INSTALLED"
        private const val HEAD = "OCBM install:"
        private const val INSTALLER = "installer:"
        private const val UNDONE = "installed and undone on this adapter before"

        fun parse(text: String): StickReport? {
            val lines = text.replace("\r", "").lines()
            val first = lines.indexOfFirst { it.isNotBlank() }
            if (first < 0 || !lines[first].startsWith(HEAD)) return null
            var at = first + 1
            val installer =
                lines
                    .getOrNull(at)
                    ?.takeIf { it.startsWith(INSTALLER) }
                    ?.removePrefix(INSTALLER)
                    ?.trim()
                    ?.also { at++ }
            val body = lines.drop(at).takeWhile { !it.startsWith("--- ") }
            return StickReport(lines[first].removePrefix(HEAD).trim(), installer, paragraphs(body))
        }

        /** once.sh breaks its sentences across lines; a blank line ends a paragraph. */
        private fun paragraphs(lines: List<String>): List<String> =
            lines
                .joinToString("\n")
                .split(Regex("\n\\s*\n"))
                .map { p -> p.lines().joinToString(" ") { it.trim() }.trim() }
                .filter { it.isNotEmpty() }
    }
}

/** A stick's volume: FAT32, whole-device or in the first MBR partition, or why not. */
sealed interface StickVolume {
    data class Fat32(
        val fs: FileSystem,
    ) : StickVolume

    data class NotFat32(
        val found: String,
    ) : StickVolume

    companion object {
        private const val MBR_ENTRY = 0x1BE
        private const val SIGNATURE_AT = 510
        private const val FS_TYPE_AT = 0x52
        private const val FAT16_TYPE_AT = 0x36
        private const val OEM_AT = 3
        private const val BYTES_PER_SECTOR_AT = 0x0B
        private const val FAT16_SIZE_AT = 0x16

        fun open(device: BlockDeviceDriver): StickVolume {
            val start = fat32Start(sector(device, 0)).getOrElse { return NotFat32(it.message.orEmpty()) }
            if (start != 0L && !isFat32Boot(sector(device, start))) return NotFat32("a FAT32 partition that holds no FAT32 file system")
            val fs = Fat32FileSystem.read(ByteBlockDevice(device, start)) ?: return NotFat32("no FAT32 file system")
            return Fat32(fs)
        }

        /** The block the FAT32 volume starts at, read from sector 0, or what sector 0 holds instead. */
        internal fun fat32Start(s0: ByteArray): Result<Long> =
            when {
                u8(s0, SIGNATURE_AT) != 0x55 || u8(s0, SIGNATURE_AT + 1) != 0xAA -> fail("no partition table or file system")
                isFat32Boot(s0) -> Result.success(0L)
                else -> otherBoot(s0)?.let(::fail) ?: firstPartition(s0)
            }

        /** The file system a whole-stick boot sector that is not FAT32 holds; null when sector 0 is a partition table. */
        private fun otherBoot(s0: ByteArray): String? {
            val oem = text(s0, OEM_AT, 8).trim()
            return when {
                oem == "EXFAT" || oem == "NTFS" -> oem.replace("EXFAT", "exFAT")
                text(s0, FAT16_TYPE_AT, 3) == "FAT" -> "FAT16"
                else -> null
            }
        }

        private fun firstPartition(s0: ByteArray): Result<Long> =
            when (val type = u8(s0, MBR_ENTRY + 4)) {
                0x0B, 0x0C -> le32(s0, MBR_ENTRY + 8).takeIf { it > 0 }?.let { Result.success(it) } ?: fail("a FAT32 partition that starts nowhere")
                0x00 -> fail("an empty partition table")
                0xEE -> fail("a GPT partition table")
                0x07 -> fail("exFAT or NTFS")
                0x01, 0x04, 0x06, 0x0E -> fail("FAT16")
                else -> fail("a partition of type 0x%02x".format(type))
            }

        /** A FAT32 boot sector: a jump, 512..4096-byte sectors, no FAT16 size, and "FAT32" where FAT32 puts its type. */
        internal fun isFat32Boot(b: ByteArray): Boolean =
            (u8(b, 0) == 0xEB || u8(b, 0) == 0xE9) &&
                le16(b, BYTES_PER_SECTOR_AT) in setOf(512, 1024, 2048, 4096) &&
                le16(b, FAT16_SIZE_AT) == 0 &&
                text(b, FS_TYPE_AT, 5) == "FAT32"

        private fun sector(
            device: BlockDeviceDriver,
            lba: Long,
        ): ByteArray {
            val buf = ByteBuffer.allocate(device.blockSize)
            device.read(lba, buf)
            return buf.array()
        }

        private fun fail(what: String): Result<Long> = Result.failure(IOException(what))
    }
}

// Sector fields, little-endian as FAT and the MBR keep them.

private fun u8(
    b: ByteArray,
    at: Int,
): Int = b[at].toInt() and 0xFF

private fun le16(
    b: ByteArray,
    at: Int,
): Int = u8(b, at) or (u8(b, at + 1) shl 8)

private fun le32(
    b: ByteArray,
    at: Int,
): Long = le16(b, at).toLong() or (le16(b, at + 2).toLong() shl 16)

private fun text(
    b: ByteArray,
    at: Int,
    n: Int,
): String = String(b, at, n, Charsets.US_ASCII)

/** What is on the stick before anything is written. [freeBytes] is null when the volume does not say. */
data class StickContents(
    val report: StickReport?,
    val hasImage: Boolean,
    val freeBytes: Long?,
    val reclaimableBytes: Long,
)

/**
 * The installer onto the stick, as once.sh wants it: A15W_Update.img at the root, and an empty
 * OCBM_RETRY beside it only for a deliberate retry of a version that was undone. The image is written as
 * A15W_Update.img.part, read back and checked against its SHA-256, and only then renamed, so the adapter
 * never finds a partial image under the name it installs from.
 */
object StickFiles {
    const val IMAGE = InstallerImage.FILE_NAME
    const val PART = "$IMAGE.part"
    const val RESULT = "OCBM_RESULT.txt"
    const val RETRY = "OCBM_RETRY"

    /** once.sh refuses a stick with less free than this: the flash backup goes there. */
    const val BACKUP_ROOM = 40L * 1024 * 1024
    private const val CHUNK = 64 * 1024

    fun roomNeeded(imageBytes: Int): Long = BACKUP_ROOM + imageBytes

    fun inspect(fs: FileSystem): StickContents {
        val root = fs.rootDirectory
        val image = root.search(IMAGE)
        val reclaimable = (image?.length ?: 0) + (root.search(PART)?.length ?: 0)
        val free = fs.freeSpace.takeIf { it in 0..fs.capacity }
        return StickContents(readReport(fs), image != null, free, reclaimable)
    }

    /** True when the free space (counting a replaced image) is short of [roomNeeded]; unknown free space is not short. */
    fun tooFull(
        contents: StickContents,
        imageBytes: Int,
    ): Boolean = contents.freeBytes != null && contents.freeBytes + contents.reclaimableBytes < roomNeeded(imageBytes)

    fun readReport(fs: FileSystem): StickReport? = fs.rootDirectory.search(RESULT)?.let { StickReport.parse(String(readAll(it), Charsets.UTF_8)) }

    /** The image is still at the root under its own name: the installer has not run from this stick. */
    fun imageWaiting(fs: FileSystem): Boolean = fs.rootDirectory.search(IMAGE) != null

    /** Write [image] (whose SHA-256 is [sha256]) as A15W_Update.img, replacing one already there, plus OCBM_RETRY when [retry]. */
    fun write(
        fs: FileSystem,
        image: ByteArray,
        sha256: String,
        retry: Boolean,
        progress: (done: Long, total: Long) -> Unit,
    ) {
        val root = fs.rootDirectory
        root.search(PART)?.delete()
        val part = root.createFile(PART)
        var at = 0
        while (at < image.size) {
            val n = minOf(CHUNK, image.size - at)
            part.write(at.toLong(), ByteBuffer.wrap(image, at, n))
            at += n
            progress(at.toLong(), image.size.toLong())
        }
        part.flush()
        part.close()
        val back = root.search(PART) ?: throw IOException("the written file is not on the stick")
        if (InstallerImage.sha256Hex(readAll(back)) != sha256.lowercase()) {
            back.delete()
            throw IOException("what the stick gave back does not match the installer")
        }
        root.search(IMAGE)?.delete()
        back.name = IMAGE
        if (retry && root.search(RETRY) == null) root.createFile(RETRY).close()
    }

    private fun readAll(file: UsbFile): ByteArray {
        val out = ByteArray(file.length.toInt())
        var at = 0
        while (at < out.size) {
            val n = minOf(CHUNK, out.size - at)
            file.read(at.toLong(), ByteBuffer.wrap(out, at, n))
            at += n
        }
        return out
    }
}
