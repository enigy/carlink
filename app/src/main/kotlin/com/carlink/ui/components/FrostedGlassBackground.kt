package com.carlink.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.carlink.ui.settings.BackgroundLogoConfig
import kotlin.random.Random

// ── Tunables ────────────────────────────────────────────────────────────────
// Blur over the background scene. Large enough that the mountains read as color and light
// only, small enough that the sunset and the logo still show through as shapes.
private val GLASS_BLUR_RADIUS = 28.dp

/** The glass is blurred at 1/BLUR_DOWNSCALE of the screen size, then scaled back up. */
private const val BLUR_DOWNSCALE = 4f

// Dark tint over the blurred scene so the settings text keeps its contrast. Below API 31
// Modifier.blur is a no-op (no RenderEffect), so the sharp scene gets a heavier tint instead.
// gminfo3.7 is AAOS 12 (API 31/32) and gets the blur.
private const val GLASS_TINT_ALPHA = 0.45f
private const val GLASS_TINT_ALPHA_NO_BLUR = 0.75f

// Grain: a tiled noise texture over the glass. It gives the dithered, frosted look and hides
// the banding a smooth blur shows on the radio's panel. Seeded, so the pattern never changes.
private const val GRAIN_TILE_PX = 128
private const val GRAIN_MAX_ALPHA = 22 // of 255, per pixel
private const val GRAIN_SEED = 220L
// ────────────────────────────────────────────────────────────────────────────

/**
 * Frosted-glass backdrop for the settings page: the connecting screen's background scene
 * ([BackgroundLogoConfig.drawableRes], so it follows the Background Logo setting), blurred,
 * tinted and grained.
 *
 * Opaque by construction once in place — the scene fills the bounds and the blur clamps at the
 * edges (as [BlurredEdgeTreatment.Rectangle] does) — so the projection's SurfaceView never shows
 * through it. The scene is a static image (no fog animation), so the blurred layer is drawn once
 * and reused.
 *
 * It slides in and out with the rest of the settings page ([settingsSlideIn]); the frost itself
 * is constant.
 */
@Composable
fun FrostedGlassBackground(
    backgroundLogo: BackgroundLogoConfig,
    modifier: Modifier = Modifier,
) {
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val tint = MaterialTheme.colorScheme.surface.copy(alpha = if (blurSupported) GLASS_TINT_ALPHA else GLASS_TINT_ALPHA_NO_BLUR)
    val grain = remember { ShaderBrush(ImageShader(grainTile(), TileMode.Repeated, TileMode.Repeated)) }

    Box(modifier = modifier.fillMaxSize()) {
        // Blurred at 1/BLUR_DOWNSCALE size, then scaled up: the blur pass touches 1/16 of the
        // pixels, and a blurred image has no detail for the upscale to lose.
        Image(
            painter = painterResource(id = backgroundLogo.drawableRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier.align(Alignment.TopStart).fillMaxSize(1f / BLUR_DOWNSCALE).graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = BLUR_DOWNSCALE
                    scaleY = BLUR_DOWNSCALE
                    val radius = GLASS_BLUR_RADIUS.toPx() / BLUR_DOWNSCALE
                    renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    clip = true
                },
        )
        Box(modifier = Modifier.fillMaxSize().background(tint))
        Box(modifier = Modifier.fillMaxSize().background(grain))
    }
}

/** One tile of grain: each pixel black or white at a random alpha up to [GRAIN_MAX_ALPHA]. */
private fun grainTile(): ImageBitmap {
    val random = Random(GRAIN_SEED)
    val pixels =
        IntArray(GRAIN_TILE_PX * GRAIN_TILE_PX) {
            val alpha = random.nextInt(GRAIN_MAX_ALPHA + 1)
            val gray = if (random.nextBoolean()) 0xFF else 0x00
            (alpha shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
    return Bitmap.createBitmap(pixels, GRAIN_TILE_PX, GRAIN_TILE_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
}
