package com.carlink.ui.settings

import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.ui.components.NO_BACKGROUND_BRUSH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Scenes per row in the picker. */
private const val PER_ROW = 3

/** Thumbnails decode to about this width: six full 2400 px scenes would cost ~90 MB of bitmaps. */
private const val THUMB_WIDTH_PX = 480

/** The connecting screen is 2400x966; the thumbnails crop to the same shape. */
private const val SCENE_ASPECT = 2400f / 966f

/**
 * The Loading screen choice as a grid of picture cards: the two brand scenes and the brand-free
 * photos. Applied the moment it is tapped (app-only setting; the box never sees it).
 */
@Composable
internal fun LoadingScreenPicker(
    selected: BackgroundLogoConfig,
    onSelect: (BackgroundLogoConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BackgroundLogoConfig.entries.chunked(PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { scene ->
                    SceneCard(scene, scene == selected, onClick = { onSelect(scene) }, modifier = Modifier.weight(1f))
                }
                repeat(PER_ROW - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SceneCard(
    scene: BackgroundLogoConfig,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainer,
        border = BorderStroke(3.dp, if (selected) colors.primary else colors.surfaceContainer),
    ) {
        Column {
            SceneThumbnail(scene.drawableRes, Modifier.fillMaxWidth().aspectRatio(SCENE_ASPECT))
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selected) Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = colors.primary, modifier = Modifier.size(26.dp))
                Text(scene.brandName, fontSize = 22.sp, color = if (selected) colors.primary else colors.onSurface)
            }
        }
    }
}

@Composable
private fun SceneThumbnail(
    @DrawableRes res: Int?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, res) {
        value = res?.let { withContext(Dispatchers.IO) { decodeThumbnail(context.resources, it, THUMB_WIDTH_PX) } }
    }
    // The gradient is "No background" itself, and the placeholder while a photo thumbnail decodes.
    Box(modifier = modifier.clip(RoundedCornerShape(topStart = 21.dp, topEnd = 21.dp)).background(NO_BACKGROUND_BRUSH)) {
        bitmap?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** Decode [id] at roughly [targetWidth] px: power-of-two subsampling, no density scaling. */
private fun decodeThumbnail(
    resources: Resources,
    @DrawableRes id: Int,
    targetWidth: Int,
): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeResource(resources, id, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
    val opts =
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
        }
    return BitmapFactory.decodeResource(resources, id, opts)?.asImageBitmap()
}
