package com.carlink.convert

import com.carlink.ocbm.RawBulkTransport
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A stick in memory: 512-byte blocks, only those written are kept, the rest read as zeros, so a
 * 64 MiB stick costs what is on it. [corrupt] may change a block on its way in, like a stick that
 * drops bits.
 */
internal class MemoryStick(
    override val blocks: Long,
) : BlockDeviceDriver {
    override val blockSize: Int = BLOCK

    private val stored = HashMap<Long, ByteArray>()

    @Volatile var corrupt: ((lba: Long, block: ByteArray) -> Unit)? = null

    override fun init() = Unit

    override fun read(
        deviceOffset: Long,
        buffer: ByteBuffer,
    ) {
        var lba = deviceOffset
        while (buffer.hasRemaining()) {
            check(lba < blocks) { "read past the end: block $lba of $blocks" }
            val block = synchronized(stored) { stored[lba]?.copyOf() } ?: ByteArray(BLOCK)
            buffer.put(block, 0, minOf(BLOCK, buffer.remaining()))
            lba++
        }
    }

    override fun write(
        deviceOffset: Long,
        buffer: ByteBuffer,
    ) {
        var lba = deviceOffset
        while (buffer.hasRemaining()) {
            check(lba < blocks) { "write past the end: block $lba of $blocks" }
            val block = ByteArray(BLOCK)
            buffer.get(block, 0, minOf(BLOCK, buffer.remaining()))
            corrupt?.invoke(lba, block)
            synchronized(stored) { stored[lba] = block }
            lba++
        }
    }

    fun block(lba: Long): ByteArray = ByteArray(BLOCK).also { read(lba, ByteBuffer.wrap(it)) }

    fun putBlock(
        lba: Long,
        bytes: ByteArray,
    ) = write(lba, ByteBuffer.wrap(bytes.copyOf(BLOCK)))

    companion object {
        const val BLOCK = 512
        const val MIB = 1024L * 1024

        /** A whole-stick FAT32 volume ("superfloppy") of [mib] MiB, as mkfs.fat lays one out. */
        fun superfloppy(mib: Int): MemoryStick = MemoryStick(mib * MIB / BLOCK).also { Fat32Format.format(it, 0, it.blocks) }

        /** An MBR stick, as most sticks come: one FAT32 partition (type 0x0C) from block 2048 to the end. */
        fun partitioned(mib: Int): MemoryStick =
            MemoryStick(mib * MIB / BLOCK).also {
                val start = 2048L
                it.putBlock(0, mbr(0x0C, start, it.blocks - start))
                Fat32Format.format(it, start, it.blocks - start)
            }

        /** Sector 0 of an MBR whose first partition has [type]. */
        fun mbr(
            type: Int,
            start: Long,
            count: Long,
        ): ByteArray {
            val s = ByteBuffer.allocate(BLOCK).order(ByteOrder.LITTLE_ENDIAN)
            s.put(0x1BE + 4, type.toByte())
            s.putInt(0x1BE + 8, start.toInt())
            s.putInt(0x1BE + 12, count.toInt())
            s.put(510, 0x55).put(511, 0xAA.toByte())
            return s.array()
        }
    }
}

/** Just enough of mkfs.fat for the tests: FAT32, 512-byte sectors and clusters, two FATs, FSInfo, an empty root. */
internal object Fat32Format {
    private const val RESERVED = 32
    private const val FATS = 2

    fun format(
        dev: MemoryStick,
        start: Long,
        sectors: Long,
    ) {
        val fatSectors = ((sectors - RESERVED) / 128 + 1) // 4-byte entries, 128 a sector, one cluster a sector
        val clusters = sectors - RESERVED - FATS * fatSectors
        val boot = ByteBuffer.allocate(MemoryStick.BLOCK).order(ByteOrder.LITTLE_ENDIAN)
        boot.put(0, 0xEB.toByte()).put(1, 0x58).put(2, 0x90.toByte())
        "mkfs.fat".toByteArray().forEachIndexed { i, b -> boot.put(3 + i, b) }
        boot.putShort(0x0B, 512).put(0x0D, 1)
        boot.putShort(0x0E, RESERVED.toShort()).put(0x10, FATS.toByte())
        boot.put(0x15, 0xF8.toByte()).putShort(0x18, 32).putShort(0x1A, 64)
        boot.putInt(0x1C, start.toInt()).putInt(0x20, sectors.toInt()).putInt(0x24, fatSectors.toInt())
        boot.putInt(0x2C, 2).putShort(0x30, 1).putShort(0x32, 6)
        boot.put(0x40, 0x80.toByte()).put(0x42, 0x29).putInt(0x43, 0x1234ABCD)
        "NO NAME    FAT32   ".toByteArray().forEachIndexed { i, b -> boot.put(0x47 + i, b) }
        boot.put(510, 0x55).put(511, 0xAA.toByte())
        val info = ByteBuffer.allocate(MemoryStick.BLOCK).order(ByteOrder.LITTLE_ENDIAN)
        info.putInt(0, 0x41615252).putInt(484, 0x61417272)
        info.putInt(488, (clusters - 1).toInt()).putInt(492, 3)
        info.put(510, 0x55).put(511, 0xAA.toByte())
        dev.putBlock(start, boot.array())
        dev.putBlock(start + 1, info.array())
        dev.putBlock(start + 6, boot.array())
        dev.putBlock(start + 7, info.array())
        val fat = ByteBuffer.allocate(MemoryStick.BLOCK).order(ByteOrder.LITTLE_ENDIAN)
        fat.putInt(0, 0x0FFFFFF8).putInt(4, 0x0FFFFFFF).putInt(8, 0x0FFFFFFF) // media, reserved, the root's one cluster
        repeat(FATS) { dev.putBlock(start + RESERVED + it * fatSectors, fat.array()) }
    }
}

/**
 * A USB stick as the bridge relays it: Bulk-Only Transport over [disk]. Each write is part of a command
 * block, or of its data; the answer (data IN, then the status) goes to the read handler at once. [onCommand]
 * may answer for the disk: a status, or null to let the disk answer.
 */
internal class FakeStickTransport(
    private val disk: MemoryStick,
) : RawBulkTransport {
    private var handler: ((ByteArray, Int) -> Unit)? = null
    private var dead: (() -> Unit)? = null
    private var pending: ByteBuffer? = null
    private var cbw: ByteBuffer? = null

    @Volatile var running = false
    val commands = mutableListOf<Int>()

    @Volatile var onCommand: ((op: Int) -> Int?)? = null

    @Volatile var badTag = false

    // What the stick still had to send when the bridge took it from the head unit's driver: sent as reading starts.
    @Volatile var leftover: ByteArray? = null

    override fun setReadHandler(handler: (ByteArray, Int) -> Unit) {
        this.handler = handler
    }

    override fun setDeadHandler(handler: () -> Unit) {
        dead = handler
    }

    override fun start() {
        running = true
        leftover?.let { deliver(it) }
    }

    override fun stop() {
        running = false
    }

    override fun writeBulk(data: ByteArray): Boolean {
        if (!running) return false
        val out = pending
        if (out != null) {
            out.put(data)
            if (!out.hasRemaining()) {
                pending = null
                finish(cbw ?: error("data with no command"), out.array())
            }
            return true
        }
        val c = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        check(data.size == 31 && c.getInt(0) == 0x43425355) { "not a CBW: ${data.size} bytes" }
        val length = c.getInt(8)
        val dataIn = (c.get(12).toInt() and 0x80) != 0
        if (length > 0 && !dataIn) {
            cbw = c
            pending = ByteBuffer.allocate(length)
        } else {
            finish(c, null)
        }
        return true
    }

    private fun finish(
        c: ByteBuffer,
        dataOut: ByteArray?,
    ) {
        val op = c.get(15).toInt() and 0xFF
        synchronized(commands) { commands += op }
        val block = ByteArray(16)
        c.position(15)
        c.get(block)
        val cb = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN)
        val forced = onCommand?.invoke(op)
        val (data, status) = if (forced != null) ByteArray(0) to forced else answer(op, cb, dataOut)
        if (data.isNotEmpty()) deliver(data)
        val csw = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        csw.putInt(0x53425355).putInt(c.getInt(4) + if (badTag) 1 else 0)
        csw.putInt(0).put(status.toByte())
        deliver(csw.array())
    }

    private fun answer(
        op: Int,
        cb: ByteBuffer,
        dataOut: ByteArray?,
    ): Pair<ByteArray, Int> =
        when (op) {
            0x12 -> ByteArray(36).also { "QEMU    QEMU HARDDISK   ".toByteArray().copyInto(it, 8) } to 0
            0x00, 0x35 -> ByteArray(0) to 0
            0x03 -> ByteArray(18).also { it[0] = 0x70 } to 0
            0x25 -> capacity() to 0
            0x28 -> {
                val out = ByteBuffer.allocate((cb.getShort(7).toInt() and 0xFFFF) * MemoryStick.BLOCK)
                disk.read(cb.getInt(2).toLong() and 0xFFFF_FFFFL, out)
                out.array() to 0
            }
            0x2A -> {
                disk.write(cb.getInt(2).toLong() and 0xFFFF_FFFFL, ByteBuffer.wrap(dataOut ?: throw IOException("no data")))
                ByteArray(0) to 0
            }
            else -> ByteArray(0) to 1
        }

    /** READ CAPACITY(10): the last block and the block size, big-endian. */
    private fun capacity(): ByteArray {
        val b = ByteBuffer.allocate(8)
        b.putInt((disk.blocks - 1).toInt()).putInt(MemoryStick.BLOCK)
        return b.array()
    }

    /** In pieces of up to 16 KiB, as the bridge's read pump hands them on. */
    private fun deliver(bytes: ByteArray) {
        var at = 0
        while (at < bytes.size) {
            val n = minOf(16 * 1024, bytes.size - at)
            handler?.invoke(bytes.copyOfRange(at, at + n), n)
            at += n
        }
    }

    /** The stick pulled out: the bridge's read loop ends. */
    fun pull() {
        running = false
        dead?.invoke()
    }
}

/** Reports as the installer writes them (enigy/ocbm once.sh, finish.sh, ocbm_revert.sh at 710eb23). */
internal object Reports {
    val undone =
        """
        OCBM install: REFUSED
        installer: 1.0.0

        This installer (1.0.0) was installed and undone on this adapter before. To try it again anyway, put an empty file named OCBM_RETRY on the stick next to the image.

        Nothing on the adapter was changed.
        Remove the stick, then unplug the adapter and plug it back in: it starts stock CarPlay as before.

        --- log
        [  3.1 s] route: stick; installer: 1.0.0; started 3 s after power-on
        """.trimIndent()

    val wrongFirmware =
        """
        OCBM install: REFUSED
        installer: 1.0.0

        This adapter runs firmware 2025.02.25.1521. The installer needs 2025.10.15.1127: update with Carlinkit's 2025.10.15.1127 image first.

        Nothing on the adapter was changed.
        Remove the stick, then unplug the adapter and plug it back in: it starts stock CarPlay as before.

        --- log
        [  3.0 s] REFUSED: This adapter runs firmware 2025.02.25.1521.
        """.trimIndent()

    val installed =
        """
        OCBM install: INSTALLED (trial)
        installer: 1.0.0

        The adapter restarts now and runs OCBM. Connect it with Carlink.
        Stock CarPlay stays on the adapter as a fallback until the Carlink session that confirms it ends.
        If OCBM fails its start-up self-test, the adapter goes back to stock CarPlay by itself.

        A full flash backup is in OCBM_BACKUP/651e on this stick. It holds the adapter's Wi-Fi
        password and Bluetooth keys: keep the stick private.

        --- log
        [ 41.2 s] backup: 3 partitions
        """.trimIndent()

    val backToStock =
        """
        OCBM install: BACK TO STOCK
        installer: 1.0.0

        The adapter went back to stock CarPlay: the self-test failed.
        Stock CarPlay and Android Auto work as before. HiCar, the Android Mirror helper and the web
        pages stay removed; Carlinkit's own 2025.10.15.1127 image puts them back.
        To try OCBM again, put the installer on the stick together with an empty file named OCBM_RETRY.

        --- self-test
        self-test: FAILED
        """.trimIndent()
}
