package com.carlink.ui.pane

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.callui.CallRoster
import com.carlink.logging.logInfo
import com.carlink.ui.settings.LayoutStore

/**
 * Carlink3's side of the split layout (Settings › Display › Layout), laid over the part of the video
 * frame CarPlay is not drawing into. The frame's own header says where that is
 * ([CarlinkManager.viewArea]), so the pane follows every step of a Dock resize, whoever asked for it:
 * Settings, the pane's own full-screen button, or CarPlay's Dock button. While CarPlay has the whole
 * frame but a split is declared, a small tab at the pane's edge brings the split back.
 *
 * The card: a call while the iPhone reports one, else the music playing, else the clock. Music puts
 * its album art behind the whole pane and sets the words and buttons light or dark to suit it.
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
    // The cover lives here, not in the pane: it stays decoded and ready while the pane is hidden
    // behind full-screen CarPlay, and while a call card has the pane.
    val media by carlinkManager.nowPlaying.collectAsState()
    val art = rememberAlbumArt(media.info.albumArt)
    val r = rect
    if (r != null) {
        Box(modifier = modifier) {
            val fraction = r.paneFraction
            if (fraction > 0f) {
                PaneHost(
                    carlinkManager,
                    art = art,
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

/** The pane itself: the backdrop, a header naming the card with the full-screen button, then the card. */
@Composable
private fun PaneHost(
    carlinkManager: CarlinkManager,
    /** The current cover, kept by the caller; shown only behind the music card. */
    art: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val layout by remember { LayoutStore.get(context) }.flow.collectAsState()
    val call by carlinkManager.callPresentationFlow.collectAsState()
    val media by carlinkManager.nowPlaying.collectAsState()
    val showCall = call != CallRoster.Presentation.None
    val showMedia = !showCall && media.hasTrack
    // A call takes the pane with the theme's plain background; the cover waits for the music's return.
    val art = if (showMedia) art else null
    val look = paneLook(art, rememberArtIsDark(art), colors.onSurface)
    val title = paneTitle(showCall, showMedia, media.info.appName)
    Surface(
        modifier =
            modifier
                // Rounded like CarPlay's own pane (Settings › Display › Layout › Pane corner radius).
                .clip(RoundedCornerShape(layout.paneCornerDp.dp))
                // Every touch that lands here is the pane's: nothing falls through to the SurfaceView.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        color = colors.surface,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            MediaBackdrop(art, modifier = Modifier.fillMaxSize())
            if (art != null) {
                Box(
                    modifier =
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(look.scrim.copy(alpha = SCRIM_TOP), Color.Transparent, look.scrim.copy(alpha = SCRIM_BOTTOM))),
                        ),
                )
            }
            CompositionLocalProvider(LocalContentColor provides look.content) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                    PaneHeader(
                        title = title,
                        // The app that is playing: a tap on its name brings it forward in CarPlay.
                        onTitle = if (showMedia) ({ carlinkManager.openCarPlayApp(NOW_PLAYING_URL) }) else null,
                        onExpand = {
                            logInfo("[UI_ACTION] Pane: CarPlay full screen", tag = "UI")
                            carlinkManager.requestViewArea(0)
                        },
                    )
                    val card = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)
                    when {
                        showCall -> CallCard(presentation = call, onButton = { carlinkManager.sendTelephony(it) }, modifier = card)
                        showMedia -> MediaCard(state = media, onControl = carlinkManager::mediaControl, modifier = card)
                        else -> ClockCard(modifier = card)
                    }
                }
            }
        }
    }
}

/** The colour of the words and buttons, and of the scrim behind them, for what is behind the pane. */
private class PaneLook(
    val content: Color,
    val scrim: Color,
)

/** Over art the words are white or near-black for the cover; without art the theme's own colour. */
private fun paneLook(
    art: ImageBitmap?,
    dark: Boolean,
    onSurface: Color,
): PaneLook =
    when {
        art == null -> PaneLook(onSurface, Color.Black)
        dark -> PaneLook(Color.White, Color.Black)
        else -> PaneLook(INK, Color.White)
    }

/** The header's word for the card: the phone, the app playing (its own name), or this app. */
private fun paneTitle(
    showCall: Boolean,
    showMedia: Boolean,
    appName: String?,
): String =
    when {
        showCall -> "Phone"
        showMedia -> appName?.takeIf { it.isNotBlank() } ?: "Music"
        else -> "Carlink3"
    }

@Composable
private fun PaneHeader(
    title: String,
    onTitle: (() -> Unit)?,
    onExpand: () -> Unit,
) {
    val content = LocalContentColor.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val titleModifier = if (onTitle != null) Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onTitle).padding(horizontal = 8.dp, vertical = 6.dp) else Modifier
        Text(title, style = MaterialTheme.typography.titleMedium, color = content.copy(alpha = HEADER_ALPHA), modifier = titleModifier)
        Spacer(modifier = Modifier.weight(1f))
        PaneButton(Icons.Default.Fullscreen, "CarPlay full screen", onClick = onExpand, size = 56.dp, iconSize = 32.dp, tint = content.copy(alpha = HEADER_ALPHA))
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
        color = colors.surface.copy(alpha = TAB_ALPHA),
        shape = MaterialTheme.shapes.medium,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(Icons.Default.VerticalSplit, contentDescription = "Show the Carlink3 pane", modifier = Modifier.size(28.dp), tint = colors.onSurface)
        }
    }
}

private val INK = Color(0xFF14181E)

/** CarPlay's URL for the app that is playing now (Apple's `requestUI` scheme; being benched on this box). */
private const val NOW_PLAYING_URL = "nowplaying:"
private const val SCRIM_TOP = 0.45f
private const val SCRIM_BOTTOM = 0.75f
private const val HEADER_ALPHA = 0.85f
private const val TAB_ALPHA = 0.7f
