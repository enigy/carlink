package com.carlink.ocbm.seam

import kotlin.math.roundToInt

/**
 * The view area iOS is drawing CarPlay into, read from the 128-byte `AirPlayScreenHeader` of each
 * opcode-1 (codec config) record on the video seam. During a Dock resize iOS sends one of these per
 * animation step (255 in one 3 s switch, box log 2026-09-05), with the coded size constant and the
 * crop moving — so this is the one truth for where CarPlay's picture ends and Carlink3's pane begins,
 * whoever asked for the switch. `docs/carplay/06_AV_PIPELINE.md` §3 has the layout, read out of
 * `CarPlaySDK.framework` (`_AirPlayReceiverSessionScreen_ProcessFrames`).
 *
 * All six values are `f32` little-endian in the header: the coded size at `+0x10/+0x14`, the area's
 * origin at `+0x20/+0x24` and its size at `+0x28/+0x2c`.
 */
data class ViewAreaRect(
    val codedWidth: Int,
    val codedHeight: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    /** CarPlay covers the whole coded frame: no room for a pane. */
    val isFull: Boolean get() = x == 0 && y == 0 && width >= codedWidth && height >= codedHeight

    /** The share of the frame's width left of or right of CarPlay's area, 0 when it is full. */
    val paneFraction: Float get() = if (codedWidth <= 0) 0f else (codedWidth - width).coerceIn(0, codedWidth).toFloat() / codedWidth

    /** The free band is on the left: CarPlay's area starts inside the frame. */
    val paneOnLeft: Boolean get() = x > 0

    override fun toString(): String = "${width}x$height@$x,$y of ${codedWidth}x$codedHeight"

    companion object {
        const val CODED_WIDTH_OFF = 0x10
        const val CODED_HEIGHT_OFF = 0x14
        const val X_OFF = 0x20
        const val Y_OFF = 0x24
        const val WIDTH_OFF = 0x28
        const val HEIGHT_OFF = 0x2c
        private const val NEEDED = HEIGHT_OFF + 4

        /**
         * The rect in the header at [off] of [b], or null when the bytes do not describe one (a short
         * buffer, a non-finite float, a zero size or a negative origin). Never throws: this runs on the
         * USB read thread, where an exception takes every channel down.
         */
        fun parse(
            b: ByteArray,
            off: Int = 0,
        ): ViewAreaRect? {
            if (off < 0 || off + NEEDED > b.size) return null
            val v = FloatArray(6)
            val offsets = intArrayOf(CODED_WIDTH_OFF, CODED_HEIGHT_OFF, X_OFF, Y_OFF, WIDTH_OFF, HEIGHT_OFF)
            for (i in offsets.indices) {
                val f = Float.fromBits(le32(b, off + offsets[i]))
                if (!f.isFinite() || f < 0f || f > MAX_PX) return null
                v[i] = f
            }
            val r = ViewAreaRect(v[0].roundToInt(), v[1].roundToInt(), v[2].roundToInt(), v[3].roundToInt(), v[4].roundToInt(), v[5].roundToInt())
            if (minOf(r.codedWidth, r.codedHeight, r.width, r.height) <= 0) return null
            return r
        }

        /** Larger than any panel; a header that is not a rect decodes to huge or tiny floats. */
        private const val MAX_PX = 16_384f

        private fun le32(
            b: ByteArray,
            off: Int,
        ): Int =
            (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}
