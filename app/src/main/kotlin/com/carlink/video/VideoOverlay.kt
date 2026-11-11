package com.carlink.video

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Route A's screen: the phone's video, full screen over CarPlay while [VideoPlayback.showing] (parked only), with
 * a Close button. CarPlay goes on underneath; the screen takes every touch, so none reaches it while the video
 * is up. The picture keeps the video's own aspect ratio, black around it.
 */
@Composable
fun VideoOverlay(
    playback: VideoPlayback,
    engine: ExoVideoEngine,
) {
    val showing by playback.showing.collectAsState()
    if (!showing) return
    val player by engine.player.collectAsState()
    val size by engine.size.collectAsState()
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                },
    ) {
        val p = player
        if (p != null) {
            val ratio = size?.let { it.width * it.pixelWidthHeightRatio / it.height }
            AndroidView(
                factory = { context -> SurfaceView(context) },
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .then(if (ratio != null && ratio > 0f) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize()),
                update = { view -> p.setVideoSurfaceView(view) },
                onRelease = { view -> p.clearVideoSurfaceView(view) },
            )
        }
        FilledTonalIconButton(
            onClick = { playback.close() },
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(64.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close video", modifier = Modifier.size(36.dp))
        }
    }
}
