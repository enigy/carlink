package com.carlink.convert

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A USB stick spoken to as a SCSI disk over USB Mass Storage Bulk-Only Transport, through the bridge.
 *
 * The bridge opens the stick on request ([StickTransport], bridge API 4) and claims its one bulk
 * interface with force, which detaches the kernel's usb-storage driver: the head unit's own mount of the
 * stick goes away, and nothing else writes to it while Carlink does. Each command is Bulk-Only's three
 * phases, each a transfer of the length the protocol fixes: the 31-byte command block (CBW) out, the
 * data in or out, and the 13-byte status (CSW) in. Nothing is read that was not asked for: a stick
 * expects an IN only after a command block, and the bridge's free-running read lost the data and padded
 * the status on the emulator bench's stick (29 Oct).
 *
 * When a phase fails the host recovers as Bulk-Only says: a stalled data phase clears the halt and
 * reads the status; a status that won't come is asked once more after clearing the IN halt; a status
 * out of step, or none at all, is reset recovery (Bulk-Only Mass Storage Reset, then both halts
 * cleared) and an IOException. The first command is asked again after a failure: the head unit's
 * driver may have been mid-command when the bridge took the stick.
 *
 * A libaums [BlockDeviceDriver]: offsets are block numbers, buffers whole blocks. [trace] (debug builds)
 * gets a line for each phase, with the first 16 bytes of what came back, and the bridge's own record of
 * each transfer.
 */
@Suppress("TooManyFunctions") // Bulk-Only's three phases and each step of its recovery get one apiece
class BulkOnlyStorage(
    private val stick: StickTransport,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val trace: ((String) -> Unit)? = null,
) : BlockDeviceDriver {
    private var tag = 0

    override var blockSize: Int = DEFAULT_BLOCK
        private set

    override var blocks: Long = 0
        private set

    /** INQUIRY (asked again once, after recovery, should whatever the head unit left get in the way), TEST UNIT READY and READ CAPACITY. */
    override fun init() {
        val inquiry = runCatching { inquire() }.getOrElse { inquire() }
        if ((inquiry[0].toInt() and PERIPHERAL_TYPE) != DIRECT_ACCESS) throw IOException("the USB device is not a disk")
        waitReady()
        val cap = command(byteArrayOf(READ_CAPACITY, 0, 0, 0, 0, 0, 0, 0, 0, 0), dataIn = CAPACITY_LEN)
        val b = ByteBuffer.wrap(cap.copyOf(CAPACITY_LEN)).order(ByteOrder.BIG_ENDIAN) // a short answer reads as no size
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
            val data = command(rw(READ_10, lba, count), dataIn = count * blockSize)
            if (data.size != count * blockSize) throw IOException("the stick sent ${data.size} of ${count * blockSize} bytes")
            buffer.put(data)
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
            command(rw(WRITE_10, lba, count), dataOut = data, statusWaitMs = WRITE_STATUS_MS)
            lba += count
        }
    }

    /** SYNCHRONIZE CACHE: the stick's own write cache to its flash. */
    fun sync() {
        command(byteArrayOf(SYNC_CACHE, 0, 0, 0, 0, 0, 0, 0, 0, 0), statusWaitMs = WRITE_STATUS_MS)
    }

    /** Let go of the stick: the bridge releases the interface, and the head unit may mount it again. */
    fun release() {
        stick.close()
    }

    private fun inquire(): ByteArray = command(byteArrayOf(INQUIRY, 0, 0, 0, INQUIRY_LEN.toByte(), 0), dataIn = INQUIRY_LEN)

    /** TEST UNIT READY until it passes: a failure asks REQUEST SENSE, which clears the "unit attention" a claim leaves. */
    private fun waitReady() {
        repeat(READY_TRIES) {
            if (runCatching { command(byteArrayOf(TEST_UNIT_READY, 0, 0, 0, 0, 0)) }.isSuccess) return
            Thread.sleep(READY_PAUSE_MS)
        }
        throw IOException("the stick never became ready")
    }

    private fun requestSense(): ByteArray = command(byteArrayOf(REQUEST_SENSE, 0, 0, 0, SENSE_LEN.toByte(), 0), dataIn = SENSE_LEN, check = false)

    /** One command's three phases; the data IN (empty when none). Throws unless its status is good (when [check]). */
    private fun command(
        cb: ByteArray,
        dataIn: Int = 0,
        dataOut: ByteArray? = null,
        statusWaitMs: Int = timeoutMs,
        check: Boolean = true,
    ): ByteArray {
        val t = ++tag
        try {
            val length = dataOut?.size ?: dataIn
            val sent = stick.write(cbw(t, length, dataOut == null && dataIn > 0, cb), timeoutMs)
            log("CBW tag $t op 0x%02x, %d B %s: %s".format(cb[0], length, if (dataOut != null) "out" else "in", if (sent) "sent" else "NOT SENT"))
            if (!sent) {
                recover()
                throw IOException("the command did not reach the stick")
            }
            val data = dataPhase(dataIn, dataOut)
            val status = status(t, statusWaitMs)
            if (check && status != STATUS_OK) {
                val sense = if (status == STATUS_FAILED) runCatching { requestSense() }.getOrNull() else null
                throw IOException("SCSI 0x%02x failed (status %d%s)".format(cb[0], status, sense?.let { ", " + senseText(it) }.orEmpty()))
            }
            return data
        } finally {
            bridgeTrace()
        }
    }

    /** The data phase, IN or OUT; a stall there clears the endpoint's halt, so the status can still be read. */
    private fun dataPhase(
        dataIn: Int,
        dataOut: ByteArray?,
    ): ByteArray {
        if (dataOut != null) {
            val sent = stick.write(dataOut, timeoutMs)
            log("data out ${dataOut.size} B: ${if (sent) "sent" else "FAILED"}")
            if (!sent) clearHalt(inEndpoint = false)
            return EMPTY
        }
        if (dataIn == 0) return EMPTY
        val got = stick.read(dataIn, timeoutMs)
        log("data in ${got?.size ?: "nothing"} of $dataIn B${got?.let { ": " + hex(it) }.orEmpty()}")
        if (got == null) clearHalt(inEndpoint = true)
        return got ?: EMPTY
    }

    /** The status of command [t] from its CSW: asked once more after clearing the IN halt; reset recovery and an IOException when it is not to be had. */
    private fun status(
        t: Int,
        waitMs: Int,
    ): Int {
        val csw =
            stick.read(CSW_LEN, waitMs) ?: run {
                clearHalt(inEndpoint = true)
                stick.read(CSW_LEN, waitMs)
            }
        val b = csw?.takeIf { it.size == CSW_LEN }?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
        val status = b?.takeIf { it.getInt(0) == CSW_SIGNATURE && it.getInt(4) == t }?.get(STATUS_AT)?.toInt()
        log("CSW for tag $t: ${csw?.let { "${it.size} B: ${hex(it)}" } ?: "nothing"}${status?.let { ", status $it" }.orEmpty()}")
        if (status == null || status == STATUS_PHASE_ERROR) {
            recover()
            throw IOException(
                when {
                    csw == null -> "the stick gave no status"
                    status == null -> "the stick's answer is out of step"
                    else -> "the stick reported a phase error"
                },
            )
        }
        return status
    }

    private fun clearHalt(inEndpoint: Boolean) {
        val done = stick.clearHalt(inEndpoint)
        log("clear halt ${if (inEndpoint) "IN" else "OUT"}: ${if (done) "done" else "FAILED"}")
    }

    /** Reset recovery: Bulk-Only Transport back to waiting for a command block. */
    private fun recover() {
        val done = stick.resetBulkOnly()
        log("reset recovery: ${if (done) "done" else "FAILED"}")
    }

    private fun log(line: String) {
        trace?.invoke(line)
    }

    /** The bridge's own record of each transfer, after each command (debug builds). */
    private fun bridgeTrace() {
        val t = trace ?: return
        val lines = stick.takeTrace().lines()
        lines.filter { it.isNotBlank() }.forEach { t("bridge: $it") }
    }

    companion object {
        const val TIMEOUT_MS = 10_000
        const val WRITE_STATUS_MS = 30_000
        const val CBW_LEN = 31
        const val CSW_LEN = 13
        const val CBW_SIGNATURE = 0x43425355
        const val CSW_SIGNATURE = 0x53425355
        const val STATUS_OK = 0
        const val STATUS_FAILED = 1
        const val STATUS_PHASE_ERROR = 2
        const val INQUIRY: Byte = 0x12
        const val TEST_UNIT_READY: Byte = 0x00
        const val REQUEST_SENSE: Byte = 0x03
        const val READ_CAPACITY: Byte = 0x25
        const val READ_10: Byte = 0x28
        const val WRITE_10: Byte = 0x2A
        const val SYNC_CACHE: Byte = 0x35

        /** 64 KiB at 512-byte blocks: four of the bridge's 16 KiB transfers per command. */
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

        /** The first 16 bytes, in hex. */
        private fun hex(b: ByteArray): String = b.take(HEX_BYTES).joinToString(" ") { "%02x".format(it) } + if (b.size > HEX_BYTES) " …" else ""

        private fun senseText(sense: ByteArray): String =
            if (sense.size >= SENSE_LEN) {
                "sense %x/%02x/%02x".format(sense[2].toInt() and 0x0F, sense[12].toInt() and 0xFF, sense[13].toInt() and 0xFF)
            } else {
                "no sense data"
            }
    }
}
