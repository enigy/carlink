package com.carlink.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Route A's player: Media3 ExoPlayer with its HLS module, for the AirPlay video the phone offers inside the
 * CarPlay session ([VideoPlayback] drives it). The player lives on the main thread, as ExoPlayer requires, and
 * is built only when the phone queues something, then freed at [stop]. [snapshot] is readable from any thread:
 * it is refreshed on every player event and twice a second while playing.
 *
 * Its sound goes to the media stream without asking for audio focus: Carlink's CarPlay audio keeps its own
 * focus handling, and the phone stops its own audio when it sends the video here. Errors are logged by code,
 * never with the address.
 *
 * [mayPlay] is asked again on the main thread as the player starts: a play request decided on another thread
 * can reach the player after the car stopped being known to be parked, and then it pauses instead.
 */
class ExoVideoEngine(
    private val context: Context,
    /** Whether the car is known to be parked now ([com.carlink.CarlinkManager.isParked]). */
    private val mayPlay: () -> Boolean,
    /**
     * Where a stream is fetched from at its load: through the phone's network once it offered its proxy
     * ([com.carlink.video.proxy.TunnelDataSource]), or null for this unit's own.
     */
    private val network: () -> DataSource.Factory?,
) : VideoEngine {
    private val main = Handler(Looper.getMainLooper())

    private val playerState = MutableStateFlow<ExoPlayer?>(null)

    /** The live player, for the screen to draw into ([VideoOverlay]); null when nothing is loaded. */
    val player: StateFlow<ExoPlayer?> = playerState.asStateFlow()

    private val sizeState = MutableStateFlow<VideoSize?>(null)

    /** The video's size once known, for the screen's aspect ratio. */
    val size: StateFlow<VideoSize?> = sizeState.asStateFlow()

    @Volatile private var snap = VideoEngine.Snapshot()

    @Volatile override var onFailed: ((code: String) -> Unit)? = null

    private val listener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) = publish()

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                sizeState.value = videoSize.takeIf { it.width > 0 && it.height > 0 }
            }

            override fun onPlayerError(error: PlaybackException) {
                logWarn("[VIDEO] the player failed: ${error.errorCodeName}", tag = Logger.Tags.ADAPTR)
                snap = snap.copy(failed = true, ready = false, rate = 0.0)
                onFailed?.invoke(error.errorCodeName)
            }
        }

    private val ticker =
        object : Runnable {
            override fun run() {
                publish()
                if (playerState.value?.isPlaying == true) main.postDelayed(this, TICK_MS)
            }
        }

    @OptIn(UnstableApi::class) // a media source over a data source of its own
    override fun load(
        url: String,
        startS: Double,
    ) = onMain {
        val p = playerState.value ?: build()
        val startMs = (startS * MS_PER_S).toLong().coerceAtLeast(0)
        val viaPhone = network()
        if (viaPhone != null) {
            p.setMediaSource(DefaultMediaSourceFactory(viaPhone).createMediaSource(mediaItem(url)), startMs)
        } else {
            p.setMediaItem(mediaItem(url), startMs)
        }
        p.playWhenReady = false
        p.prepare()
        snap = VideoEngine.Snapshot(positionS = startS)
        val over = if (viaPhone != null) "over the phone's network" else "over this unit's own network"
        logInfo("[VIDEO] loading (${if (isHls(url)) "HLS" else "by its extension"}), from $startS s, $over", tag = Logger.Tags.ADAPTR)
    }

    override fun setRate(rate: Double) =
        onMain {
            val p = playerState.value ?: return@onMain
            if (rate <= 0.0 || !mayPlay()) {
                p.pause()
            } else {
                p.setPlaybackSpeed(rate.toFloat())
                p.play()
            }
            publish()
            main.removeCallbacks(ticker)
            main.post(ticker)
        }

    override fun seek(toS: Double) =
        onMain {
            playerState.value?.seekTo((toS * MS_PER_S).toLong().coerceAtLeast(0))
        }

    override fun stop() =
        onMain {
            main.removeCallbacks(ticker)
            playerState.value?.let {
                it.removeListener(listener)
                it.release()
            }
            playerState.value = null
            sizeState.value = null
            snap = VideoEngine.Snapshot()
        }

    override fun snapshot(): VideoEngine.Snapshot = snap

    private fun build(): ExoPlayer =
        ExoPlayer.Builder(context).build().also {
            it.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false,
            )
            it.addListener(listener)
            playerState.value = it
        }

    /** Main thread only: the player's state into [snap]. */
    private fun publish() {
        val p = playerState.value ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET }?.let { it / MS_PER_S } ?: 0.0
        snap =
            VideoEngine.Snapshot(
                // Still ready at the item's end, its position at its duration, as LIVI reports an end: not ready reads as a failure.
                ready = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED,
                rate = if (p.isPlaying) p.playbackParameters.speed.toDouble() else 0.0,
                positionS = p.currentPosition / MS_PER_S,
                durationS = duration,
                bufferedS = p.bufferedPosition / MS_PER_S,
                buffering = p.playbackState == Player.STATE_BUFFERING,
                failed = snap.failed && p.playbackState == Player.STATE_IDLE,
            )
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        private const val MS_PER_S = 1000.0
        private const val TICK_MS = 500L

        /** The item for [url]: HLS when its path ends in `.m3u8` or has no extension (AirPlay video's usual form). */
        fun mediaItem(url: String): MediaItem {
            val b = MediaItem.Builder().setUri(url)
            if (isHls(url)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
            return b.build()
        }

        /** Whether [url] is taken for an HLS playlist: a `.m3u8` path, or a path with no extension at all. */
        fun isHls(url: String): Boolean {
            val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return false
            val last = path.substringAfterLast('/')
            return last.endsWith(".m3u8", ignoreCase = true) || !last.contains('.')
        }
    }
}
