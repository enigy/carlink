package com.carlink.ui.pane

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.CarlinkManager
import com.carlink.logging.logInfo
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Carlink3's side of the split layout (Settings › Display › Layout), laid over the part of the video
 * frame CarPlay is not drawing into. The frame's own header says where that is
 * ([CarlinkManager.viewArea]), so the pane follows every step of a Dock resize, whoever asked for it:
 * Settings, the pane's own full-screen button, or CarPlay's Dock button. While CarPlay has the whole
 * frame but a split is declared, a small tab at the pane's edge brings the split back.
 *
 * Sits ABOVE the SurfaceView and takes its own touches, so CarPlay — which still normalises touch over
 * the whole frame — never sees a tap meant for the pane.
 */
@Composable
fun SplitPane(
    carlinkManager: CarlinkManager,
    modifier: Modifier = Modifier,
) {
    val rect by carlinkManager.viewArea.collectAsState()
    val declared = carlinkManager.declaredSplitArea
    val r = rect
    if (r != null) {
        Box(modifier = modifier) {
            val fraction = r.paneFraction
            if (fraction > 0f) {
                PaneHost(
                    onExpand = {
                        logInfo("[UI_ACTION] Pane: CarPlay full screen", tag = "UI")
                        carlinkManager.requestViewArea(0)
                    },
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .align(if (r.paneOnLeft) Alignment.CenterStart else Alignment.CenterEnd),
                )
            } else if (declared != null) {
                SplitTab(
                    onClick = {
                        logInfo("[UI_ACTION] Pane: back to split", tag = "UI")
                        carlinkManager.requestViewArea(1)
                    },
                    modifier = Modifier.align(if (declared.x > 0) Alignment.CenterStart else Alignment.CenterEnd),
                )
            }
        }
    }
}

/** The pane itself: a header with the full-screen button, then the card. Cards beyond the clock come later. */
@Composable
private fun PaneHost(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        // Every touch that lands here is the pane's: nothing falls through to the SurfaceView.
        modifier = modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        color = colors.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Carlink3", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = onExpand, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "CarPlay full screen", modifier = Modifier.size(32.dp), tint = colors.onSurfaceVariant)
                }
            }
            ClockCard(modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** The edge tab shown while CarPlay has the whole frame: one tap back to the split. */
@Composable
private fun SplitTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.width(48.dp).height(112.dp),
        color = colors.surface.copy(alpha = 0.7f),
        shape = MaterialTheme.shapes.medium,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(Icons.Default.VerticalSplit, contentDescription = "Show the Carlink3 pane", modifier = Modifier.size(28.dp), tint = colors.onSurface)
        }
    }
}

/** The idle card: the time, in the head unit's 12- or 24-hour style, and the date. Ticks on the second. */
@Composable
private fun ClockCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timeFormat = remember(context) { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm") }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(MILLIS_PER_SECOND - System.currentTimeMillis() % MILLIS_PER_SECOND)
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(now.format(timeFormat), style = MaterialTheme.typography.displayLarge.copy(fontSize = 112.sp, lineHeight = 120.sp))
        Text(now.format(DATE_FORMAT), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private const val MILLIS_PER_SECOND = 1_000L
