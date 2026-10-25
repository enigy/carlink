package com.carlink.ui.pane

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.callui.CallRoster
import com.carlink.logging.logInfo
import com.carlink.ocbm.Ocbm

/**
 * Carlink3's side of the split layout (Settings › Display › Layout), laid over the part of the video
 * frame CarPlay is not drawing into. The frame's own header says where that is
 * ([CarlinkManager.viewArea]), so the pane follows every step of a Dock resize, whoever asked for it:
 * Settings, the pane's own full-screen button, or CarPlay's Dock button. While CarPlay has the whole
 * frame but a split is declared, a small tab at the pane's edge brings the split back.
 *
 * The card: a call while the iPhone reports one, else the music playing, else the clock.
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
                    carlinkManager,
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

/** The pane itself: a header naming the card with the full-screen button, then the card. */
@Composable
private fun PaneHost(
    carlinkManager: CarlinkManager,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val call by carlinkManager.callPresentationFlow.collectAsState()
    val media by carlinkManager.nowPlaying.collectAsState()
    val title =
        when {
            call != CallRoster.Presentation.None -> "Phone"
            media.hasTrack -> media.info.appName?.takeIf { it.isNotBlank() } ?: "Music"
            else -> "Carlink3"
        }
    Surface(
        // Every touch that lands here is the pane's: nothing falls through to the SurfaceView.
        modifier = modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        color = colors.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        logInfo("[UI_ACTION] Pane: CarPlay full screen", tag = "UI")
                        carlinkManager.requestViewArea(0)
                    },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "CarPlay full screen", modifier = Modifier.size(32.dp), tint = colors.onSurfaceVariant)
                }
            }
            val card = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)
            when {
                call != CallRoster.Presentation.None ->
                    CallCard(
                        presentation = call,
                        onAnswer = { carlinkManager.sendTelephony(Ocbm.TEL_ANSWER) },
                        onEnd = { carlinkManager.sendTelephony(Ocbm.TEL_END) },
                        modifier = card,
                    )
                media.hasTrack -> MediaCard(state = media, onControl = carlinkManager::mediaControl, modifier = card)
                else -> ClockCard(modifier = card)
            }
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
