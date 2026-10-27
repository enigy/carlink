package com.carlink.ui.pane

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * A round pane control that visibly answers a tap: it snaps smaller and a highlight covers it the
 * moment the finger lands, and it eases back over [RELEASE_MS] after the finger lifts — but never
 * sooner than [FLASH_MS] after the tap, so a quick tap in the car still shows. The first version
 * animated INTO the pressed look, and a tap released before the animation got anywhere (10-25:
 * mute and the keypad "did not show any presses").
 */
@Suppress("LongParameterList") // the words, the look and the content
@Composable
internal fun PaneControl(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    fill: Color = Color.Transparent,
    border: Color? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // A tap's flash outlives the press so even a 30 ms tap is seen.
    var tappedAt by remember { mutableLongStateOf(0L) }
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(tappedAt) {
        if (tappedAt > 0L) {
            flashing = true
            delay(FLASH_MS)
            flashing = false
        }
    }
    val down = pressed || flashing
    val scale by animateFloatAsState(if (down) PRESSED_SCALE else 1f, animationSpec = tween(if (down) PRESS_MS else RELEASE_MS), label = "scale")
    val highlight by animateFloatAsState(if (down) HIGHLIGHT_ALPHA else 0f, animationSpec = tween(if (down) PRESS_MS else RELEASE_MS), label = "highlight")
    val content = LocalContentColor.current
    val pressEffect =
        Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    Box(
        modifier =
            modifier
                .size(size)
                .then(pressEffect)
                .clip(CircleShape)
                .background(fill)
                .then(if (border != null) Modifier.border(2.dp, border, CircleShape) else Modifier)
                .clickable(interactionSource = interaction, indication = ripple(), onClickLabel = label) {
                    tappedAt = System.nanoTime()
                    onClick()
                },
        contentAlignment = Alignment.Center,
    ) {
        content()
        Box(modifier = Modifier.fillMaxSize().background(content.copy(alpha = highlight)))
    }
}

/** A [PaneControl] with an icon. */
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
    border: Color? = null,
) {
    PaneControl(label = label, onClick = onClick, modifier = modifier, size = size, fill = fill, border = border) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** A [PaneControl] with a character on it: one key of the in-call keypad. */
@Composable
internal fun PaneKey(
    key: Char,
    onClick: () -> Unit,
    fill: Color,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    PaneControl(label = key.toString(), onClick = onClick, modifier = modifier, size = 76.dp, fill = fill) {
        Text(key.toString(), style = MaterialTheme.typography.headlineMedium, color = tint)
    }
}

private const val PRESSED_SCALE = 0.86f
private const val HIGHLIGHT_ALPHA = 0.28f
private const val PRESS_MS = 40
private const val RELEASE_MS = 220
private const val FLASH_MS = 180L
