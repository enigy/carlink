package com.carlink.ui.pane

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.carlink.logging.logInfo
import com.carlink.media.MediaControl
import com.carlink.media.NowPlayingState
import com.carlink.media.SyncedLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The album art for the whole pane. The last cover stays up while the next track's has not arrived
 * (iOS sends the title first and the art a moment later), so there is no placeholder between songs:
 * [MediaBackdrop] crossfades straight from one cover to the next.
 *
 * Spotify's stand-in. On every skip (log 10-25 21:22) the same 18 KB stand-in image arrives under
 * the OLD title, the title then changes with the stand-in still attached, and the real cover lands
 * 0.6–0.7 s later. The stand-in is the same bytes every time, so it is learned: a cover replaced
 * before it has stood for [ART_SETTLE_MS] is remembered as a stand-in ([ArtStandIns]) and refused on
 * sight from then on. Until one is known every cover waits out the settle; once one is known every
 * other cover is shown the moment it lands. Decoded off the main thread.
 *
 * Call this where it stays composed — the pane's host, not the pane — so the cover is kept, and the
 * next one decoded, while the pane is hidden behind full-screen CarPlay or a call card.
 */
@Composable
internal fun rememberAlbumArt(bytes: ByteArray?): ImageBitmap? {
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    // The cover waiting out its settle; left set when a newer cover cancels the wait, which is the tell.
    var pendingHash by remember { mutableIntStateOf(0) }
    LaunchedEffect(bytes) {
        if (bytes == null) return@LaunchedEffect
        val hash = bytes.contentHashCode()
        if (pendingHash != 0 && pendingHash != hash) {
            ArtStandIns.learn(pendingHash)
            logInfo("[MEDIA] album art stand-in learned (${bytes.size} B replaced it inside ${ART_SETTLE_MS}ms)", tag = "UI")
        }
        pendingHash = 0
        if (ArtStandIns.isStandIn(hash)) return@LaunchedEffect
        if (!ArtStandIns.anyKnown) {
            pendingHash = hash
            delay(ART_SETTLE_MS)
            pendingHash = 0
        }
        val decoded = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        if (decoded != null) shown = decoded
    }
    return shown
}

/** Covers known to be stand-ins (by content hash), for the life of the process. */
internal object ArtStandIns {
    private val hashes = HashSet<Int>()

    val anyKnown: Boolean get() = synchronized(hashes) { hashes.isNotEmpty() }

    fun isStandIn(hash: Int): Boolean = synchronized(hashes) { hash in hashes }

    fun learn(hash: Int) {
        synchronized(hashes) { hashes.add(hash) }
    }
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

/**
 * The art filling the pane, crossfaded between tracks; [background] (the pane's day or night colour)
 * when there is none. The part behind the words is FROSTED: the same cover drawn again blurred,
 * fading in from clear a quarter inch ([FROST_LEAD]) above the title to full at the title's top
 * ([wordsTopInRoot], the music card's measured title position in root coordinates; the band falls
 * back to [FROST_FALLBACK] of the height until the card has been laid out). Grainy, high-contrast
 * art put its texture right behind the title (owner, 10-26: "bac" in "Backyard" hard to read at a
 * glance); a darker scrim alone leaves the dark grains, the blur turns them into smooth colour while
 * the cover stays whole and sharp above the words. The first cut started the fade at a fixed 38 % of
 * the pane, well above the title (owner, 10-26: "bring it down closer to the Title text start").
 *
 * [frostAll] (the clock while music plays, [PaneBackdrop.FROSTED]) raises the band to the top so the
 * whole cover is frosted, gliding over [FROST_ALL_MS] as the driver swipes from the music to the clock
 * and back; the plain background crossfades in when the art goes (owner, 10-27).
 */
@Composable
internal fun MediaBackdrop(
    image: ImageBitmap?,
    background: Color,
    wordsTopInRoot: Float,
    modifier: Modifier = Modifier,
    frostAll: Boolean = false,
) {
    val cover by animateFloatAsState(if (frostAll) 1f else 0f, tween(FROST_ALL_MS), label = "frost all")
    Crossfade(targetState = image, animationSpec = tween(ART_FADE_MS), label = "album art", modifier = modifier) { img ->
        if (img != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().frostedBand(wordsTopInRoot, cover))
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(background))
        }
    }
}

/**
 * Blurs the content and keeps only the band from [FROST_LEAD] above [wordsTopInRoot] (clear) to the
 * words' top (full) and everything below it. Root coordinates, so the band stays put under the padded
 * column and the pager; this layer's own root offset is measured and taken off.
 */
@Composable
private fun Modifier.frostedBand(
    wordsTopInRoot: Float,
    cover: Float,
): Modifier {
    var selfTopInRoot by remember { mutableFloatStateOf(Float.NaN) }
    val lead = with(LocalDensity.current) { FROST_LEAD.toPx() }
    return this
        .onGloballyPositioned { selfTopInRoot = it.positionInRoot().y }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val measured = !wordsTopInRoot.isNaN() && !selfTopInRoot.isNaN()
            val band = if (measured) wordsTopInRoot - selfTopInRoot else size.height * FROST_FALLBACK
            // [cover] lifts the band to the top: at 1 the fade sits just above the pane, frost everywhere.
            val full = band * (1f - cover)
            val clear = (band - lead).coerceAtLeast(0f) * (1f - cover) - lead * cover
            drawRect(
                brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = clear, endY = full.coerceAtLeast(clear + 1f)),
                blendMode = BlendMode.DstIn,
            )
        }.blur(FROST_RADIUS)
}

/**
 * The music card's words and controls, over the backdrop: the song's lyrics when it has timed ones
 * ([LyricsLines]), title, artist and album, a progress bar that runs between the iPhone's position
 * ticks, and the transport row: shuffle, previous, play/pause, next, repeat. The three in the middle
 * send the same HID taps the steering wheel does. Shuffle and repeat show the phone's own modes; the
 * car has no way to set them (CarPlay's media remote has no such keys), so a tap goes to [onModes],
 * which puts CarPlay's Now Playing up, where the phone's own buttons change them. Colours come from
 * `LocalContentColor`, set by the pane for the art behind.
 */
@Suppress("LongParameterList") // the state, its three kinds of tap, the lyrics, where the words start
@Composable
internal fun MediaCard(
    state: NowPlayingState,
    onControl: (MediaControl) -> Unit,
    modifier: Modifier = Modifier,
    /** The song's timed lyrics ([rememberLyrics]), or null for none. */
    lyrics: SyncedLyrics? = null,
    /** A tap on shuffle or repeat. */
    onModes: () -> Unit = {},
    /** The first words' top edge (the lyrics', else the title's) in root coordinates; the pane frosts the art from there. */
    onWordsTop: (Float) -> Unit = {},
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(modifier = Modifier.weight(1f))
        Column(modifier = Modifier.onGloballyPositioned { onWordsTop(it.positionInRoot().y) }, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (lyrics != null) LyricsLines(lyrics, state)
            TrackWords(state)
        }
        ProgressRow(state)
        TransportRow(state = state, onControl = onControl, onModes = onModes)
    }
}

/** Now (elapsed realtime), run forward four times a second while [state] plays: the clock the progress bar and the lyrics read the position by. */
@Composable
internal fun rememberPlaybackClock(state: NowPlayingState): LongState {
    val now = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.playing, state.positionAtMs) {
        now.longValue = SystemClock.elapsedRealtime()
        while (state.playing) {
            delay(PROGRESS_TICK_MS)
            now.longValue = SystemClock.elapsedRealtime()
        }
    }
    return now
}

@Composable
private fun TrackWords(
    state: NowPlayingState,
    modifier: Modifier = Modifier,
) {
    val info = state.info
    val secondary = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    Column(modifier = modifier) {
        Text(info.title.orEmpty(), style = MaterialTheme.typography.headlineMedium.scaled(WORD_SCALE), maxLines = 2, overflow = TextOverflow.Ellipsis)
        val line2 = listOfNotNull(info.artist?.takeIf { it.isNotBlank() }, info.album?.takeIf { it.isNotBlank() }).joinToString(" · ")
        if (line2.isNotEmpty()) {
            Text(
                line2,
                style = MaterialTheme.typography.titleMedium.scaled(WORD_SCALE),
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
    val clock = rememberPlaybackClock(state)
    val position = state.positionAt(clock.longValue)
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
            val clock = MaterialTheme.typography.labelLarge.scaled(WORD_SCALE)
            Text(NowPlayingState.clock(position), style = clock, color = content.copy(alpha = SECONDARY_ALPHA))
            Text(if (duration > 0L) NowPlayingState.clock(duration) else "", style = clock, color = content.copy(alpha = SECONDARY_ALPHA))
        }
    }
}

/**
 * Shuffle at the left, previous, a filled play/pause disc that shows the state the phone reports, next,
 * and repeat at the right (owner, 10-27: prototype A, "Beside the transport buttons").
 */
@Composable
private fun TransportRow(
    state: NowPlayingState,
    onControl: (MediaControl) -> Unit,
    onModes: () -> Unit,
) {
    val playing = state.playing
    val content = LocalContentColor.current
    val onDisc = if (content.luminance() > HALF) Color.Black else Color.White
    // Previous and next on their own discs, three quarters the size of play's, ringed in the text colour.
    val ring = content.copy(alpha = RING_ALPHA)
    val discFill = content.copy(alpha = DISC_ALPHA)
    val repeat = state.repeating
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        ModeButton(Icons.Default.Shuffle, if (state.shuffling) "Shuffle on" else "Shuffle off", on = state.shuffling, onClick = onModes)
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
        ModeButton(
            if (repeat == NowPlayingState.REPEAT_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
            when (repeat) {
                NowPlayingState.REPEAT_ONE -> "Repeat one"
                NowPlayingState.REPEAT_ALL -> "Repeat all"
                else -> "Repeat off"
            },
            on = repeat != null,
            onClick = onModes,
        )
    }
}

/** The card's words and times, [WORD_SCALE] times Material's sizes: readable from the driver's seat (owner, 10-26: "increase the font in the media pane by 50 %"). */
internal fun TextStyle.scaled(factor: Float): TextStyle =
    copy(
        fontSize = fontSize * factor,
        lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
    )

internal const val WORD_SCALE = 1.5f
private val FROST_RADIUS = 24.dp

/** How far above the title the frost begins: a quarter inch (owner, 10-26). */
private val FROST_LEAD = 40.dp

/** Where the frost is full until the card has reported its title's position: a fraction of the pane's height. */
private const val FROST_FALLBACK = 0.6f
private const val PROGRESS_TICK_MS = 250L
private const val ART_FADE_MS = 600

/** How long the frost takes to cover the whole cover, or draw back to the band. */
private const val FROST_ALL_MS = 700
private const val ART_SETTLE_MS = 1_000L
private const val TONE_SAMPLE = 16
private const val SECONDARY_ALPHA = 0.72f
private const val TRACK_ALPHA = 0.25f
private const val RING_ALPHA = 0.6f
private const val DISC_ALPHA = 0.14f
private const val HALF = 0.5f
