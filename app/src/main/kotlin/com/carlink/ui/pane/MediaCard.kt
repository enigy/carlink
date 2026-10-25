package com.carlink.ui.pane

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlink.media.MediaControl
import com.carlink.media.NowPlayingState
import kotlinx.coroutines.delay

/**
 * The pane's music card: album art across the width, the track's words, a progress bar that runs
 * between the iPhone's position ticks, and the three transport buttons. The buttons send the same HID
 * taps the steering wheel does, so what works there works here.
 */
@Composable
internal fun MediaCard(
    state: NowPlayingState,
    onControl: (MediaControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AlbumArt(state.info.albumArt, modifier = Modifier.fillMaxWidth())
        TrackWords(state)
        Spacer(modifier = Modifier.weight(1f))
        ProgressRow(state)
        TransportRow(playing = state.playing, onControl = onControl)
    }
}

/** The track's art, square, or a note on a tile until it arrives. Decoded once per track. */
@Composable
private fun AlbumArt(
    bytes: ByteArray?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // One decode per art blob (iAP2 art is a few hundred KB at most), on the identity of the bytes.
    val image: ImageBitmap? = remember(bytes) { bytes?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } }
    Box(
        modifier = modifier.aspectRatio(1f).clip(MaterialTheme.shapes.large).background(colors.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        } else {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(96.dp))
        }
    }
}

@Composable
private fun TrackWords(state: NowPlayingState) {
    val colors = MaterialTheme.colorScheme
    val info = state.info
    Column {
        Text(info.title.orEmpty(), style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val line2 = listOfNotNull(info.artist?.takeIf { it.isNotBlank() }, info.album?.takeIf { it.isNotBlank() }).joinToString(" · ")
        if (line2.isNotEmpty()) {
            Text(
                line2,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant,
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
    val colors = MaterialTheme.colorScheme
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
            drawStopIndicator = {},
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(NowPlayingState.clock(position), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Text(if (duration > 0L) NowPlayingState.clock(duration) else "", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun TransportRow(
    playing: Boolean,
    onControl: (MediaControl) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onControl(MediaControl.PREVIOUS) }, modifier = Modifier.size(72.dp)) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(44.dp))
        }
        IconButton(onClick = { onControl(MediaControl.PLAY_PAUSE) }, modifier = Modifier.size(88.dp).clip(CircleShape).background(colors.onSurface)) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = colors.surface,
                modifier = Modifier.size(52.dp),
            )
        }
        IconButton(onClick = { onControl(MediaControl.NEXT) }, modifier = Modifier.size(72.dp)) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(44.dp))
        }
    }
}

private const val PROGRESS_TICK_MS = 250L
