package com.carlink.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Carlinkit's protocol as the conversion reads it: frames, what the adapter says it is, and the offer. */
class StockProtocolTest {
    private val got = mutableListOf<Pair<Int, String>>()
    private val frames = StockFrames({ type, payload -> got += type to String(payload, Charsets.US_ASCII) }, maxPayload = 64)

    @Test
    fun `whole frames come out in order`() {
        frames.push(frame(0xCC, "2025.10.15.1127CAY") + frame(0x19, "{}"))
        assertEquals(listOf(0xCC to "2025.10.15.1127CAY", 0x19 to "{}"), got)
    }

    @Test
    fun `a frame split anywhere is put back together`() {
        val bytes = frame(0xCC, "2025.10.15.1127CAY") + frame(0xAA, "")
        for (cut in 1 until bytes.size) {
            got.clear()
            frames.push(bytes.copyOfRange(0, cut))
            frames.push(bytes.copyOfRange(cut, bytes.size))
            assertEquals("cut at $cut", listOf(0xCC to "2025.10.15.1127CAY", 0xAA to ""), got)
        }
    }

    @Test
    fun `only the first length bytes of a reused buffer count`() {
        val bytes = frame(0x19, "{}")
        frames.push(bytes + ByteArray(40) { 0x55 }, bytes.size)
        assertEquals(listOf(0x19 to "{}"), got)
    }

    @Test
    fun `junk before a frame and a header whose type check is wrong are stepped over`() {
        val bad = frame(0x19, "x").also { it[12] = 0 } // ~type no longer matches
        frames.push(byteArrayOf(1, 2, 3) + bad + frame(0xCC, "v"))
        assertEquals(listOf(0xCC to "v"), got)
    }

    @Test
    fun `a payload over the limit is skipped, even across pushes, and the next frame still arrives`() {
        val big = frame(0x06, "v".repeat(100)) // video, which the conversion never reads
        frames.push(big.copyOfRange(0, 30))
        frames.push(big.copyOfRange(30, 90))
        frames.push(big.copyOfRange(90, big.size) + frame(0xCC, "after"))
        assertEquals(listOf(0xCC to "after"), got)
    }

    @Test
    fun `the firmware version is read out of SoftwareVersion`() {
        assertEquals("2025.10.15.1127", StockInfo.firmwareOf("2025.10.15.1127CAY\u0000\u0000".toByteArray()))
        assertEquals("2025.02.25.1521", StockInfo.firmwareOf("2025.02.25.1521CAY".toByteArray()))
        assertNull(StockInfo.firmwareOf("garbage".toByteArray()))
    }

    @Test
    fun `the product type is read out of the adapter's BoxSettings`() {
        val json = """{"uuid":"651e","boxType":"YA","productType":"A15W","hwVersion":"YMA0-WR2C-0003"}"""
        assertEquals("A15W", StockInfo.productOf((json + "\u0000").toByteArray()))
        assertNull("the phone's BoxSettings has none", StockInfo.productOf("""{"MDModel":"iPhone18,4"}""".toByteArray()))
        assertNull(StockInfo.productOf("not json".toByteArray()))
    }

    @Test
    fun `only an A15W on 2025_10_15_1127 is offered the conversion`() {
        val ready = StockInfo(firmware = REQUIRED_FIRMWARE, product = REQUIRED_PRODUCT)
        assertEquals(Offer.CONVERT, offerFor(ready, imageReady = true))
        assertEquals(Offer.NO_IMAGE, offerFor(ready, imageReady = false))
        assertEquals(Offer.UPDATE_FIRST, offerFor(ready.copy(firmware = "2025.02.25.1521"), imageReady = true))
        assertEquals(Offer.UPDATE_FIRST, offerFor(ready.copy(firmware = null), imageReady = true))
        assertEquals(Offer.UNSUPPORTED, offerFor(ready.copy(product = "A15H"), imageReady = true))
        assertEquals(Offer.UNSUPPORTED, offerFor(StockInfo(), imageReady = true))
        assertTrue(ready.complete)
        assertFalse(StockInfo(firmware = REQUIRED_FIRMWARE).complete)
    }

    @Test
    fun `stock adapters are 1314_1520 and 1314_1521, not the OCBM adapter`() {
        assertTrue(StockAdapters.isStock(0x1314, 0x1520))
        assertTrue(StockAdapters.isStock(0x1314, 0x1521))
        assertFalse(StockAdapters.isStock(0x1314, 0x2d00))
        assertFalse(StockAdapters.isStock(0x1315, 0x1520))
        assertEquals("1314:1520", StockAdapters.usbId(0x1314, 0x1520))
        assertEquals("1314:2d00", StockAdapters.usbId(0x1314, 0x2d00))
    }

    @Test
    fun `a shared stick is the gadget's 1d6b_a4a5, the bench's is QEMU's 46f4_0001`() {
        assertTrue(SharedStick.isAdapterStick(0x1d6b, 0xa4a5))
        assertFalse(SharedStick.isAdapterStick(0x1d6b, 0x0002)) // a Linux root hub
        assertFalse(SharedStick.isAdapterStick(0x46f4, 0x0001))
        assertTrue(SharedStick.isBenchStick(0x46f4, 0x0001))
        assertFalse(SharedStick.isBenchStick(0x1d6b, 0xa4a5))
        assertEquals("the stock firmware installs from this name", "A15W_Update.img", InstallerImage.FILE_NAME)
        assertTrue("the adapter's share: bcdDevice 4.09", SharedStick.looksLikeAdapterShare(0x1d6b, 0xa4a5, "4.09"))
        assertFalse(SharedStick.looksLikeAdapterShare(0x1d6b, 0xa4a5, "6.01"))
        assertFalse(SharedStick.looksLikeAdapterShare(0x1d6b, 0xa4a5, null))
        assertFalse(SharedStick.looksLikeAdapterShare(0x46f4, 0x0001, "4.09"))
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
}
