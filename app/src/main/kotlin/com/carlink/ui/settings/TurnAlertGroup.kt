package com.carlink.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.carlink.logging.logWarn
import com.carlink.navigation.TurnAlert
import kotlinx.coroutines.delay

/**
 * Settings › Cluster › Turn alert (design "Turn approach alert", 2026-10-21): flash the cluster
 * and HUD turn icon as the car nears a turn ([TurnAlert]). Rows on the left, a live preview of the
 * chosen color, style and speed on the right.
 */
@Composable
internal fun TurnAlertGroup(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { TurnAlertStore.get(context) }
    val cfg by store.flow.collectAsState()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel("Turn alert", modifier = Modifier.padding(top = 8.dp))
        SettingsGroup {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    SwitchRow(
                        title = "Turn alert",
                        subtitle = "Flash the turn icon on the cluster and HUD as you approach the next turn or exit.",
                        checked = cfg.enabled,
                        icon = Icons.Default.FlashOn,
                        onCheckedChange = {
                            logWarn("[UI_ACTION] Turn alert -> $it", tag = "UI")
                            store.setEnabled(it)
                        },
                    )
                    TurnAlertRows(cfg, store, Modifier.alpha(if (cfg.enabled) 1f else DISABLED_ALPHA))
                }
                TurnAlertPreview(cfg, Modifier.padding(24.dp))
            }
        }
    }
}

@Composable
private fun TurnAlertRows(
    cfg: TurnAlertConfig,
    store: TurnAlertStore,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        GroupDivider()
        ChoiceSetting(
            title = "Start alerting",
            subtitle = timingHint(cfg.timing),
            options = AlertTiming.entries,
            selected = cfg.timing,
            icon = Icons.Default.Timer,
            onSelect = {
                logWarn("[UI_ACTION] Turn alert: start -> ${it.code}", tag = "UI")
                store.setTiming(it)
            },
        )
        GroupDivider()
        ChoiceSetting(
            title = "Flash speed",
            subtitle = "One flash every ${seconds(cfg.speed.cycleMs)}",
            options = FlashSpeed.entries,
            selected = cfg.speed,
            icon = Icons.Default.Speed,
            onSelect = {
                logWarn("[UI_ACTION] Turn alert: speed -> ${it.code}", tag = "UI")
                store.setSpeed(it)
            },
        )
        GroupDivider()
        ColorRow(cfg, store)
        GroupDivider()
        ChoiceSetting(
            title = "Style",
            subtitle = "Inlay keeps the white outline; Outline adds a colored edge",
            options = AlertStyle.entries,
            selected = cfg.style,
            icon = Icons.Default.TurnRight,
            onSelect = {
                logWarn("[UI_ACTION] Turn alert: style -> ${it.code}", tag = "UI")
                store.setStyle(it)
            },
        )
        GroupDivider()
        SwitchRow(
            title = "Solid for the last 3 seconds",
            subtitle = "Stop flashing just before the turn and hold the colored icon",
            checked = cfg.solidLast,
            icon = Icons.Default.Adjust,
            onCheckedChange = {
                logWarn("[UI_ACTION] Turn alert: solid last 3 s -> $it", tag = "UI")
                store.setSolidLast(it)
            },
        )
    }
}

@Composable
private fun ColorRow(
    cfg: TurnAlertConfig,
    store: TurnAlertStore,
) {
    SettingRow(title = "Inlay color", subtitle = cfg.color.label, icon = Icons.Default.Palette) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InlayColor.entries.forEach { c ->
                Swatch(c, selected = c == cfg.color) {
                    logWarn("[UI_ACTION] Turn alert: color -> ${c.code}", tag = "UI")
                    store.setColor(c)
                }
            }
        }
    }
}

@Composable
private fun Swatch(
    color: InlayColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(color.argb))
                .border(width = 3.dp, color = if (selected) Color.White else Color.Transparent, shape = CircleShape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = color.label },
    )
}

/** The icon as the cluster will show it, flashing at the chosen speed while the alert is on. */
@Composable
private fun TurnAlertPreview(
    cfg: TurnAlertConfig,
    modifier: Modifier = Modifier,
) {
    var colored by remember { mutableStateOf(false) }
    LaunchedEffect(cfg.enabled, cfg.speed) {
        colored = false
        while (cfg.enabled) {
            delay(cfg.speed.cycleMs / 2)
            colored = !colored
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Preview", style = SettingsStyle.RowSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier =
                Modifier
                    .size(PREVIEW_SIZE)
                    .clip(RoundedCornerShape(16.dp))
                    .background(PREVIEW_GROUND)
                    .semantics { contentDescription = "Turn icon preview" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(ARROW_SIZE)) {
                drawArrow(colored = colored && cfg.enabled, color = Color(cfg.color.argb), outline = cfg.style == AlertStyle.OUTLINE)
            }
        }
        Text(
            text = if (cfg.enabled) "${cfg.color.label} ${cfg.style.label.lowercase()}, every ${seconds(cfg.speed.cycleMs)}" else "Turn alert is off",
            style = SettingsStyle.RowSubtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A right-turn arrow in a 0..100 box (the mockup's), drawn the way [com.carlink.navigation.TurnAlertFrames]
 * draws the cluster frames: white rim and colored core, plus a colored edge for Outline.
 */
private fun DrawScope.drawArrow(
    colored: Boolean,
    color: Color,
    outline: Boolean,
) {
    val k = size.minDimension / ARROW_BOX
    translate(left = ARROW_PAD * k, top = ARROW_PAD * k) {
        scale(scale = k, pivot = Offset.Zero) {
            val rim = Stroke(width = RIM_WIDTH, join = StrokeJoin.Round)
            if (colored) {
                if (outline) drawPath(ARROW, color, style = Stroke(width = OUTLINE_WIDTH, join = StrokeJoin.Round))
                drawPath(ARROW, color)
            } else {
                drawPath(ARROW, Color.White)
            }
            drawPath(ARROW, Color.White, style = rim)
        }
    }
}

private fun timingHint(timing: AlertTiming): String {
    fun miles(mph: Double) = TurnAlert.startMeters(mph * TurnAlert.MPS_PER_MPH, timing) / TurnAlert.METERS_PER_MILE
    return "Starts %.2f mi before a turn at 60 mph, %.2f mi at 25 mph".format(miles(60.0), miles(25.0))
}

private fun seconds(ms: Long): String = if (ms % 1000L == 0L) "${ms / 1000L} s" else "%.1f s".format(ms / 1000.0)

private const val DISABLED_ALPHA = 0.4f
private const val ARROW_BOX = 112f
private const val ARROW_PAD = 6f
private const val RIM_WIDTH = 7f
private const val OUTLINE_WIDTH = 15f
private val PREVIEW_SIZE = 200.dp
private val ARROW_SIZE = 140.dp
private val PREVIEW_GROUND = Color(0xFF04060A)

/** Quadratic corners of the mockup's path, as cubics: control points at 2/3 toward the quad's. */
private val ARROW =
    Path().apply {
        moveTo(28f, 88f)
        lineTo(28f, 52f)
        cubicTo(28f, 37.33f, 35.33f, 30f, 50f, 30f)
        lineTo(62f, 30f)
        lineTo(62f, 14f)
        lineTo(88f, 39f)
        lineTo(62f, 64f)
        lineTo(62f, 48f)
        lineTo(52f, 48f)
        cubicTo(48f, 48f, 46f, 50f, 46f, 54f)
        lineTo(46f, 88f)
        close()
    }
