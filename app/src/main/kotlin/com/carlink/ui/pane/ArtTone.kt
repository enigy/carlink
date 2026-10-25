package com.carlink.ui.pane

/**
 * Whether album art is dark or light overall, so the words and buttons laid over it can be the other
 * one. Pure: the pixels come from a 16x16 downscale of the art, the answer is the mean relative
 * luminance (Rec. 709 weights on the sRGB bytes, which is close enough for a yes/no).
 */
object ArtTone {
    /** True when the art is dark enough for white text; false for a light cover that wants dark text. */
    fun isDark(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return true
        var sum = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b
        }
        return sum / pixels.size < THRESHOLD
    }

    private const val R_WEIGHT = 0.2126
    private const val G_WEIGHT = 0.7152
    private const val B_WEIGHT = 0.0722

    /** Of 255: covers brighter than this read as light. */
    private const val THRESHOLD = 140.0
}
