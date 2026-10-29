package com.carlink.convert

import com.carlink.ocbm.RawBulkTransport
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A USB stick spoken to as a SCSI disk over USB Mass Storage Bulk-Only Transport, through the bridge.
 *
 * The bridge's `open()` claims the stick's one bulk interface with force, which detaches the kernel's
 * usb-storage driver: the head unit's own mount of the stick goes away, and nothing else writes to it
 * while Carlink does. Its writes are one bulk OUT transfer each, so a 31-byte command block (CBW) goes
 * out on its own as Bulk-Only requires; its reads arrive as one byte stream, which this splits by the
 * lengths the protocol already fixes (the data phase, then the 13-byte status, CSW). There are no
 * control transfers over the bridge, so there is no reset or clear-halt: any protocol error ends the
 * session, and the stick is taken out and put back in.
 *
 * A libaums [BlockDeviceDriver]: offsets are block numbers, buffers whole blocks. [trace] (debug builds)
 * gets a line for each command block, each data write, and each piece the bridge hands back (its length
 * and first 16 bytes), for the bench.
 */
class BulkOnlyStorage(
    private val transport: RawBulkTransport,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val trace: ((String) -> Unit)? = null,
    private val settleMs: Long = SETTLE_MS,
) : BlockDeviceDriver {
    private val inbox = ByteInbox()
    private var tag = 0

    override var blockSize: Int = DEFAULT_BLOCK
        private set

    override var blocks: Long = 0
        private set

    /**
     * Start reading, drop what the stick still had to send, then INQUIRY, TEST UNIT READY (with retries
     * while the stick settles) and READ CAPACITY. The head unit's own driver may have been mid-command when
     * the bridge took the stick from it: the rest of that answer would put every command after it out of
     * step, and without control transfers there is no reset to clear it. So it is read off and dropped first.
     */
    override fun init() {
        transport.setReadHandler { buf, n ->
            trace?.invoke("in $n B: ${hex(buf, n)}")
            inbox.put(buf, n)
        }
        transport.setDeadHandler { inbox.close() }
        transport.start()
        Thread.sleep(settleMs)
        val stale = inbox.clear()
        if (stale > 0) trace?.invoke("dropped $stale B the stick had left from before the claim")
        val inquiry = command(byteArrayOf(INQUIRY, 0, 0, 0, INQUIRY_LEN.toByte(), 0), dataIn = INQUIRY_LEN)
        if ((inquiry[0].toInt() and PERIPHERAL_TYPE) != DIRECT_ACCESS) throw IOException("the USB device is not a disk")
        waitReady()
        val cap = command(byteArrayOf(READ_CAPACITY, 0, 0, 0, 0, 0, 0, 0, 0, 0), dataIn = CAPACITY_LEN)
        val b = ByteBuffer.wrap(cap).order(ByteOrder.BIG_ENDIAN)
        val lastLba = b.getInt(0).toLong() and MASK32
        blockSize = b.getInt(4)
        if (blockSize <= 0 || lastLba == MASK32) throw IOException("the stick reports no usable size")
        blocks = lastLba + 1
    }

    override fun read(
        deviceOffset: Long,
        buffer: ByteBuffer,
    ) {
        var lba = deviceOffset
        while (buffer.hasRemaining()) {
            val count = minOf(buffer.remaining() / blockSize, MAX_BLOCKS)
            if (count <= 0) throw IOException("read of ${buffer.remaining()} bytes is not whole blocks")
            buffer.put(command(rw(READ_10, lba, count), dataIn = count * blockSize))
            lba += count
        }
    }

    override fun write(
        deviceOffset: Long,
        buffer: ByteBuffer,
    ) {
        var lba = deviceOffset
        while (buffer.hasRemaining()) {
            val count = minOf(buffer.remaining() / blockSize, MAX_BLOCKS)
            if (count <= 0) throw IOException("write of ${buffer.remaining()} bytes is not whole blocks")
            val data = ByteArray(count * blockSize)
            buffer.get(data)
            command(rw(WRITE_10, lba, count), dataOut = data, csWaitMs = WRITE_STATUS_MS)
            lba += count
        }
    }

    /** SYNCHRONIZE CACHE: the stick's own write cache to its flash. */
    fun sync() {
        command(byteArrayOf(SYNC_CACHE, 0, 0, 0, 0, 0, 0, 0, 0, 0), csWaitMs = WRITE_STATUS_MS)
    }

    /** Let go of the stick: the bridge releases the interface, and the head unit may mount it again. */
    fun release() {
        inbox.close()
        transport.stop()
    }

    /** TEST UNIT READY until it passes: a failure asks REQUEST SENSE, which clears the "unit attention" a claim leaves. */
    private fun waitReady() {
        repeat(READY_TRIES) {
            if (runCatching { command(byteArrayOf(TEST_UNIT_READY, 0, 0, 0, 0, 0)) }.isSuccess) return
            Thread.sleep(READY_PAUSE_MS)
        }
        throw IOException("the stick never became ready")
    }

    private fun requestSense(): ByteArray = command(byteArrayOf(REQUEST_SENSE, 0, 0, 0, SENSE_LEN.toByte(), 0), dataIn = SENSE_LEN, check = false)

    /** One command; the data IN (empty when none); throws unless its status is good (when [check]). */
    private fun command(
        cb: ByteArray,
        dataIn: Int = 0,
        dataOut: ByteArray? = null,
        csWaitMs: Long = timeoutMs,
        check: Boolean = true,
    ): ByteArray {
        val t = ++tag
        val length = dataOut?.size ?: dataIn
        val sent = transport.writeBulk(cbw(t, length, dataOut == null && dataIn > 0, cb))
        trace?.invoke("CBW tag $t op 0x%02x, %d B %s: %s".format(cb[0], length, if (dataOut != null) "out" else "in", if (sent) "sent" else "NOT SENT"))
        if (!sent) throw IOException("the command did not reach the stick")
        val data = if (dataIn > 0) inbox.read(dataIn, timeoutMs) else EMPTY
        dataOut?.let { out(it, "the data") }
        val status = csw(t, csWaitMs)
        if (check && status != STATUS_OK) {
            val sense = if (status == STATUS_FAILED) runCatching { requestSense() }.getOrNull() else null
            throw IOException("SCSI 0x%02x failed (status %d%s)".format(cb[0], status, sense?.let { ", " + senseText(it) }.orEmpty()))
        }
        return data
    }

    private fun out(
        bytes: ByteArray,
        what: String,
    ) {
        val sent = transport.writeBulk(bytes)
        trace?.invoke("$what out, ${bytes.size} B: ${if (sent) "sent" else "NOT SENT"}")
        if (!sent) throw IOException("$what did not reach the stick")
    }

    /** The status of the command tagged [t], from its Command Status Wrapper. */
    private fun csw(
        t: Int,
        waitMs: Long,
    ): Int {
        val csw = ByteBuffer.wrap(inbox.read(CSW_LEN, waitMs)).order(ByteOrder.LITTLE_ENDIAN)
        if (csw.getInt(0) != CSW_SIGNATURE || csw.getInt(4) != t) throw IOException("the stick's answer is out of step")
        return csw.get(STATUS_AT).toInt()
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
        const val WRITE_STATUS_MS = 30_000L

        /** For the end of an answer the head unit's driver was getting when the bridge took the stick. */
        const val SETTLE_MS = 300L
        const val CBW_LEN = 31
        const val CSW_LEN = 13
        const val CBW_SIGNATURE = 0x43425355
        const val CSW_SIGNATURE = 0x53425355
        const val STATUS_OK = 0
        const val STATUS_FAILED = 1
        const val INQUIRY: Byte = 0x12
        const val TEST_UNIT_READY: Byte = 0x00
        const val REQUEST_SENSE: Byte = 0x03
        const val READ_CAPACITY: Byte = 0x25
        const val READ_10: Byte = 0x28
        const val WRITE_10: Byte = 0x2A
        const val SYNC_CACHE: Byte = 0x35

        /** 64 KiB at 512-byte blocks: four of the bridge's 16 KiB writes per command. */
        const val MAX_BLOCKS = 128
        private const val DEFAULT_BLOCK = 512
        private const val INQUIRY_LEN = 36
        private const val CAPACITY_LEN = 8
        private const val SENSE_LEN = 18
        private const val STATUS_AT = 12
        private const val PERIPHERAL_TYPE = 0x1F
        private const val DIRECT_ACCESS = 0
        private const val READY_TRIES = 10
        private const val READY_PAUSE_MS = 300L
        private const val MASK32 = 0xFFFF_FFFFL
        private const val CB_MAX = 16
        private const val HEX_BYTES = 16
        private val EMPTY = ByteArray(0)

        /** A Command Block Wrapper: LUN 0, [cb] padded to 16 bytes. */
        fun cbw(
            tag: Int,
            dataLength: Int,
            dataIn: Boolean,
            cb: ByteArray,
        ): ByteArray =
            ByteBuffer
                .allocate(CBW_LEN)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(CBW_SIGNATURE)
                .putInt(tag)
                .putInt(dataLength)
                .put(if (dataIn) 0x80.toByte() else 0)
                .put(0)
                .put(cb.size.toByte())
                .put(cb.copyOf(CB_MAX))
                .array()

        /** READ(10) or WRITE(10) of [count] blocks at [lba]. */
        fun rw(
            op: Byte,
            lba: Long,
            count: Int,
        ): ByteArray =
            ByteBuffer
                .allocate(10)
                .order(ByteOrder.BIG_ENDIAN)
                .put(op)
                .put(0)
                .putInt(lba.toInt())
                .put(0)
                .putShort(count.toShort())
                .put(0)
                .array()

        /** The first 16 of [n] bytes, in hex. */
        private fun hex(
            b: ByteArray,
            n: Int,
        ): String = (0 until minOf(n, HEX_BYTES)).joinToString(" ") { "%02x".format(b[it]) } + if (n > HEX_BYTES) " …" else ""

        private fun senseText(sense: ByteArray): String =
            if (sense.size >= SENSE_LEN) {
                "sense %x/%02x/%02x".format(sense[2].toInt() and 0x0F, sense[12].toInt() and 0xFF, sense[13].toInt() and 0xFF)
            } else {
                "no sense data"
            }
    }
}

/** Bytes from the bridge's read thread, read back in exact lengths by the command thread. */
internal class ByteInbox {
    private val lock = Object()
    private val pending = ByteArrayOutputStream()
    private var start = 0
    private var buf = ByteArray(0)
    private var closed = false

    fun put(
        data: ByteArray,
        length: Int,
    ) {
        synchronized(lock) {
            pending.write(data, 0, length)
            lock.notifyAll()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    /** Drop everything received so far; returns how many bytes that was. */
    fun clear(): Int =
        synchronized(lock) {
            available().also {
                pending.reset()
                buf = ByteArray(0)
                start = 0
            }
        }

    /** Exactly [n] bytes, or an IOException when the link closed or [timeoutMs] passed first. */
    fun read(
        n: Int,
        timeoutMs: Long,
    ): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (available() < n) {
                if (closed) throw IOException("the stick went away")
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw IOException("the stick did not answer within ${timeoutMs / 1000} s")
                lock.wait(left)
            }
            if (start == buf.size) {
                buf = pending.toByteArray()
                pending.reset()
                start = 0
            } else if (buf.size - start < n) {
                val rest = pending.toByteArray()
                pending.reset()
                buf = buf.copyOfRange(start, buf.size) + rest
                start = 0
            }
            val out = buf.copyOfRange(start, start + n)
            start += n
            return out
        }
    }

    private fun available(): Int = buf.size - start + pending.size()
}
