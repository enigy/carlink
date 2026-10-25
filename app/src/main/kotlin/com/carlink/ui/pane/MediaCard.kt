package com.carlink.ui.pane

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlink.media.MediaControl
import com.carlink.media.NowPlayingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The album art for the whole pane. The last cover stays up while the next track's has not arrived
 * (iOS sends the title first and the art a moment later), so there is no placeholder between songs:
 * [MediaBackdrop] crossfades straight from one cover to the next. A new cover is shown only once it
 * has stood for [ART_SETTLE_MS]: on a track change Spotify first sends a stand-in image and the real
 * cover a moment later (seen in the Spotify app itself, 10-25), and a cover replaced inside the
 * window is never shown. Decoded off the main thread.
 *
 * Call this where it stays composed — the pane's host, not the pane — so the cover is kept, and the
 * next one decoded, while the pane is hidden behind full-screen CarPlay or a call card.
 */
@Composable
internal fun rememberAlbumArt(bytes: ByteArray?): ImageBitmap? {
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes) {
        if (bytes == null) return@LaunchedEffect
        delay(ART_SETTLE_MS)
        val decoded = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        if (decoded != null) shown = decoded
    }
    return shown
}

/** Whether [image] is dark overall ([ArtTone]); true without art, for the pane's own dark surface. */
@Composable
internal fun rememberArtIsDark(image: ImageBitmap?): Boolean =
    remember(image) {
        image?.let { img ->
            val small = Bitmap.createScaledBitmap(img.asAndroidBitmap(), TONE_SAMPLE, TONE_SAMPLE, true)
            val px = IntArray(TONE_SAMPLE * TONE_SAMPLE)
            small.getPixels(px, 0, TONE_SAMPLE, 0, 0, TONE_SAMPLE, TONE_SAMPLE)
            ArtTone.isDark(px)
        } ?: true
    }

/** The art filling the pane, crossfaded between tracks; the pane's surface colour when there is none. */
@Composable
internal fun MediaBackdrop(
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    Crossfade(targetState = image, animationSpec = tween(ART_FADE_MS), label = "album art", modifier = modifier) { img ->
        if (img != null) {
            Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(modifier = Modifier.fillMaxSize().background(surface))
        }
    }
}

/**
 * The music card's words and controls, over the backdrop: title, artist and album, a progress bar
 * that runs between the iPhone's position ticks, and the three transport buttons. The buttons send
 * the same HID taps the steering wheel does. Colours come from `LocalContentColor`, set by the pane
 * for the art behind.
 */
@Composable
internal fun MediaCard(
    state: NowPlayingState,
    onControl: (MediaControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(modifier = Modifier.weight(1f))
        TrackWords(state)
        ProgressRow(state)
        TransportRow(playing = state.playing, onControl = onControl)
    }
}

@Composable
private fun TrackWords(state: NowPlayingState) {
    val info = state.info
    val secondary = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    Column {
        Text(info.title.orEmpty(), style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val line2 = listOfNotNull(info.artist?.takeIf { it.isNotBlank() }, info.album?.takeIf { it.isNotBlank() }).joinToString(" · ")
        if (line2.isNotEmpty()) {
            Text(
                line2,
                style = MaterialTheme.typography.titleMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Position over duration, the position run forward four times a second while playing. */
@Composable
private fun ProgressRow(state: NowPlayingState) {
    val content = LocalContentColor.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.playing, state.positionAtMs) {
        now = SystemClock.elapsedRealtime()
        while (state.playing) {
            delay(PROGRESS_TICK_MS)
            now = SystemClock.elapsedRealtime()
        }
    }
    val position = state.positionAt(now)
    val duration = state.info.durationMs
    Column {
        LinearProgressIndicator(
            progress = { if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = content,
            trackColor = content.copy(alpha = TRACK_ALPHA),
            drawStopIndicator = {},
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(NowPlayingState.clock(position), style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = SECONDARY_ALPHA))
            Text(if (duration > 0L) NowPlayingState.clock(duration) else "", style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = SECONDARY_ALPHA))
        }
    }
}

/** Previous, a filled play/pause disc that shows the state the phone reports, next. */
@Composable
private fun TransportRow(
    playing: Boolean,
    onControl: (MediaControl) -> Unit,
) {
    val content = LocalContentColor.current
    val onDisc = if (content.luminance() > HALF) Color.Black else Color.White
    // Previous and next on their own discs, three quarters the size of play's, ringed in the text colour.
    val ring = content.copy(alpha = RING_ALPHA)
    val discFill = content.copy(alpha = DISC_ALPHA)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        PaneButton(Icons.Default.SkipPrevious, "Previous", onClick = { onControl(MediaControl.PREVIOUS) }, size = 66.dp, iconSize = 36.dp, fill = discFill, border = ring)
        PaneButton(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            if (playing) "Pause" else "Play",
            onClick = { onControl(MediaControl.PLAY_PAUSE) },
            size = 88.dp,
            iconSize = 52.dp,
            fill = content,
            tint = onDisc,
        )
        PaneButton(Icons.Default.SkipNext, "Next", onClick = { onControl(MediaControl.NEXT) }, size = 66.dp, iconSize = 36.dp, fill = discFill, border = ring)
    }
}

private const val PROGRESS_TICK_MS = 250L
private const val ART_FADE_MS = 600
private const val ART_SETTLE_MS = 1_000L
private const val TONE_SAMPLE = 16
private const val SECONDARY_ALPHA = 0.72f
private const val TRACK_ALPHA = 0.25f
private const val RING_ALPHA = 0.6f
private const val DISC_ALPHA = 0.14f
private const val HALF = 0.5f
