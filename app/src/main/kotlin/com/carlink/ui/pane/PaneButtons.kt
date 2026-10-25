package com.carlink.ui.pane

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A round pane button that visibly answers a press: it shrinks and dims while the finger is down,
 * with the ripple on top. The default IconButton's ripple alone was invisible on the head unit over
 * album art (10-25). [fill] is the disc behind the icon, transparent for a bare icon.
 */
@Suppress("LongParameterList") // the icon, its words and the five ways to size and colour it
@Composable
internal fun PaneButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    iconSize: Dp = 44.dp,
    fill: Color = Color.Transparent,
    tint: Color = LocalContentColor.current,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, label = "press")
    val pressEffect =
        Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (pressed) PRESSED_ALPHA else 1f
        }
    Box(
        modifier =
            modifier
                .size(size)
                .then(pressEffect)
                .clip(CircleShape)
                .background(fill)
                .clickable(interactionSource = interaction, indication = ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(iconSize))
    }
}

private const val PRESSED_SCALE = 0.86f
private const val PRESSED_ALPHA = 0.7f
