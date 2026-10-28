package com.carlink.convert

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.carlink.protocol.HEADER_SIZE
import com.carlink.protocol.PROTOCOL_MAGIC
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// What the app needs of Carlinkit's own USB protocol to convert a stock adapter to OCBM (OCBM one-file
// install, M5 part 3): find the adapter, read what it is, send it the installer. Frames are Carlink2's:
// a 16-byte header [0x55AA55AA][payload length][type][~type], all little-endian, then the payload.

/** A stock CPC200-CCPA on Carlinkit firmware, by USB id: 1314:1520 or 1314:1521. */
object StockAdapters {
    const val VENDOR = 0x1314
    private val PRODUCTS = setOf(0x1520, 0x1521)

    fun isStock(
        vendorId: Int,
        productId: Int,
    ): Boolean = vendorId == VENDOR && productId in PRODUCTS

    /** A stock adapter if one is attached, by USB id: readable without USB permission. */
    fun find(context: Context): UsbDevice? {
        val usb = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        return usb.deviceList.values.firstOrNull { isStock(it.vendorId, it.productId) }
    }

    /** "1314:1520", as the screens and the log show an adapter. */
    fun usbId(
        vendorId: Int,
        productId: Int,
    ): String = "${hex4(vendorId)}:${hex4(productId)}"

    private fun hex4(id: Int): String = id.toString(16).padStart(4, '0')
}

/**
 * Splits the adapter's byte stream into frames: [onFrame] gets each frame's type and payload. A header
 * whose magic or type check is wrong costs one byte and the search goes on, so a stream joined
 * mid-frame finds its feet. A payload over [maxPayload] is skipped, never buffered.
 */
class StockFrames(
    private val onFrame: (type: Int, payload: ByteArray) -> Unit,
    private val maxPayload: Int = MAX_PAYLOAD,
) {
    private val pending = ByteArrayOutputStream()
    private var skip = 0L

    /** The first [length] bytes of [data], in arrival order. */
    fun push(
        data: ByteArray,
        length: Int = data.size,
    ) {
        var from = 0
        if (skip > 0) {
            val n = minOf(skip, length.toLong()).toInt()
            skip -= n
            from = n
        }
        pending.write(data, from, length - from)
        drain()
    }

    private fun drain() {
        val buf = pending.toByteArray()
        var at = 0
        while (buf.size - at >= HEADER_SIZE) {
            val used = take(buf, at)
            if (used == 0) break
            at += used
        }
        pending.reset()
        if (at < buf.size) pending.write(buf, at, buf.size - at)
    }

    /**
     * What starts at [at]: a frame (handed on), a payload too big to keep (skipped, the rest of it through
     * [skip] as it arrives) or no header at all (one byte stepped over). Returns the bytes used up, 0 while
     * the frame there is still incomplete.
     */
    private fun take(
        buf: ByteArray,
        at: Int,
    ): Int {
        val head = ByteBuffer.wrap(buf, at, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val magic = head.int
        val length = head.int
        val type = head.int
        val check = head.int
        val inBuffer = buf.size - at - HEADER_SIZE
        return when {
            magic != PROTOCOL_MAGIC || check != type.inv() || length < 0 -> 1
            length > maxPayload -> {
                skip = maxOf(0, length - inBuffer).toLong()
                HEADER_SIZE + minOf(length, inBuffer)
            }
            inBuffer < length -> 0
            else -> {
                onFrame(type, buf.copyOfRange(at + HEADER_SIZE, at + HEADER_SIZE + length))
                HEADER_SIZE + length
            }
        }
    }

    companion object {
        /** Bigger than anything the conversion reads; video frames, which it does not, are skipped. */
        const val MAX_PAYLOAD = 256 * 1024
    }
}

/** What a stock adapter says it is: its [firmware] version (0xCC) and [product] type (0x19's `productType`). */
data class StockInfo(
    val firmware: String? = null,
    val product: String? = null,
) {
    /** Both answers are in. */
    val complete: Boolean get() = firmware != null && product != null

    companion object {
        private val VERSION = Regex("\\d{4}\\.\\d{2}\\.\\d{2}\\.\\d{4}")

        /** SoftwareVersion's payload ("2025.10.15.1127" plus a customer suffix and NULs) to the version. */
        fun firmwareOf(payload: ByteArray): String? = VERSION.find(String(payload, Charsets.US_ASCII))?.value

        /** BoxSettings' JSON from the adapter to its `productType`; null when it has none. */
        fun productOf(payload: ByteArray): String? {
            val text = String(payload, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
            return try {
                JSONObject(text).optString("productType").takeIf { it.isNotBlank() }
            } catch (_: JSONException) {
                null
            }
        }
    }
}

/** What the app can offer a stock adapter. */
enum class Offer {
    /** Convert it from the app. */
    CONVERT,

    /** Convertible, but there is no installer image to send (not published yet, or unreadable). */
    NO_IMAGE,

    /** An A15W on another firmware version: Carlinkit's own 2025.10.15.1127 update comes first. */
    UPDATE_FIRST,

    /** Not an A15W, or it would not say what it is. */
    UNSUPPORTED,
}

/**
 * The offer for [info]. The installer converts an A15W on 2025.10.15.1127 only (Stage A refuses
 * anything else, changing nothing), so only that is offered; [imageReady] is whether an installer
 * image can be had. A build with the conversion switch off never asks: it opens no stock adapter.
 */
fun offerFor(
    info: StockInfo,
    imageReady: Boolean,
): Offer =
    when {
        info.product != REQUIRED_PRODUCT -> Offer.UNSUPPORTED
        info.firmware != REQUIRED_FIRMWARE -> Offer.UPDATE_FIRST
        !imageReady -> Offer.NO_IMAGE
        else -> Offer.CONVERT
    }

/** The one product the installer converts. */
const val REQUIRED_PRODUCT = "A15W"

/** The one Carlinkit firmware the installer converts from. */
const val REQUIRED_FIRMWARE = "2025.10.15.1127"
