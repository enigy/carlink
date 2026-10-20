package com.carlink.ui.components

import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background scenes decoded at the size they are shown, not the size they ship. The brand-free
 * photos ship as their full originals (up to 6016 px wide): decoded whole they would cost up to
 * ~100 MB each, so they are subsampled and scaled to [targetWidth] while decoding. A small cache
 * lets the connecting screen and the settings glass share a decode.
 */
internal object SceneBitmaps {
    private const val CACHE_SIZE = 4
    private val cache =
        object : LinkedHashMap<String, ImageBitmap>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > CACHE_SIZE
        }

    fun cached(
        @DrawableRes res: Int,
        targetWidth: Int,
    ): ImageBitmap? = synchronized(cache) { cache[key(res, targetWidth)] }

    /** Decode [res] to about [targetWidth] px wide (never upscaled), keeping its aspect ratio. */
    fun load(
        resources: Resources,
        @DrawableRes res: Int,
        targetWidth: Int,
    ): ImageBitmap? {
        cached(res, targetWidth)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, res, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val sampledWidth = bounds.outWidth / sample
        val opts =
            BitmapFactory.Options().apply {
                inSampleSize = sample
                // Explicit densities replace the resource bucket's: the decoder scales sampledWidth
                // down to targetWidth in one pass, and a drawable/ (mdpi) file is not scaled up.
                inScaled = sampledWidth > targetWidth
                inDensity = sampledWidth
                inTargetDensity = minOf(sampledWidth, targetWidth)
            }
        val bitmap = BitmapFactory.decodeResource(resources, res, opts)?.asImageBitmap() ?: return null
        synchronized(cache) { cache[key(res, targetWidth)] = bitmap }
        return bitmap
    }

    private fun key(
        res: Int,
        width: Int,
    ) = "$res@$width"
}

/**
 * The backdrop under every scene and the whole of "No background": near-black with a faint
 * blue-green lift at the top, matching the app's dark surfaces. Drawn first, so it also shows while a
 * photo is still decoding.
 */
internal val NO_BACKGROUND_BRUSH =
    Brush.verticalGradient(listOf(Color(0xFF1C2629), Color(0xFF0B0F10), Color(0xFF000000)))

/** The scene for [res] at [targetWidth] px, decoded off the main thread; null until ready, or when [res] is null. */
@Composable
internal fun rememberScene(
    @DrawableRes res: Int?,
    targetWidth: Int,
): ImageBitmap? {
    val resources = LocalContext.current.resources
    val bitmap by produceState(res?.let { SceneBitmaps.cached(it, targetWidth) }, res, targetWidth) {
        value =
            when (res) {
                null -> null
                else -> SceneBitmaps.cached(res, targetWidth) ?: withContext(Dispatchers.IO) { SceneBitmaps.load(resources, res, targetWidth) }
            }
    }
    return bitmap
}
