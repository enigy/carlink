package com.carlink.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * "....." that pulse and wave, like a loading screen: a button's label while its press works (owner, 11-01:
 * Connect showed nothing after a tap, so the driver pressed again). A wave runs along the five dots: each rises
 * and brightens in turn, then settles back dim. [description] is what a screen reader says instead.
 */
@Composable
internal fun WorkingDots(
    description: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val transition = rememberInfiniteTransition(label = "working dots")
    val phase = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing)), label = "wave")
    val rise = with(LocalDensity.current) { RISE.toPx() }
    Row(modifier = modifier.semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        repeat(DOTS) { i ->
            Text(
                ".",
                style = style,
                modifier =
                    Modifier.graphicsLayer {
                        // Read in the draw phase only: the wave moves without recomposing anything.
                        val wave = sin(2 * PI * (phase.value - i * STAGGER)).toFloat()
                        translationY = -max(0f, wave) * rise
                        alpha = MIN_ALPHA + (1f - MIN_ALPHA) * (wave + 1f) / 2f
                    },
            )
        }
    }
}

private const val DOTS = 5
private const val CYCLE_MS = 1_100

/** How far behind the dot before it each dot moves, in parts of a cycle: the wave's travel along the row. */
private const val STAGGER = 0.12
private const val MIN_ALPHA = 0.35f
private val RISE = 6.dp
