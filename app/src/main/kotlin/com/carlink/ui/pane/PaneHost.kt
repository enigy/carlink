package com.carlink.ui.pane

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.callui.CallRoster
import com.carlink.car.VehicleData
import com.carlink.logging.logInfo
import com.carlink.media.NowPlayingState
import com.carlink.navigation.NavigationStateManager
import com.carlink.ocbm.CarPlayUrls
import kotlinx.coroutines.delay

/**
 * Carlink3's side of the split layout (Settings › Display › Layout), laid over the part of the video
 * frame CarPlay is not drawing into. The frame's own header says where that is
 * ([CarlinkManager.viewArea]), so the pane follows every step of a Dock resize, whoever asked for it:
 * Settings, the pane's own full-screen button, or CarPlay's Dock button. While CarPlay has the whole
 * frame but a split is declared, a small tab at the pane's edge brings the split back.
 *
 * The card: a call while the iPhone reports one; otherwise one of the deck ([PaneDeck]) — the route,
 * the music, the clock, the truck — swiped between ([PaneDeckPager]), the music up by default. Music puts its
 * album art behind the whole pane and sets the words and buttons light or dark to suit it.
 *
 * Sits ABOVE the SurfaceView and takes its own touches, so CarPlay — which still normalises touch over
 * the whole frame — never sees a tap meant for the pane. Nothing extra is needed for that: Material's
 * `Surface` carries an empty `pointerInput` of its own, and Compose hit-tests stop at the topmost
 * sibling that has one, so no pane touch reaches the view below ([PaneSwipeBandTest] counts them).
 * The "backstop" that used to consume every unclaimed change on the Final pass was the swipe bug
 * (owner, 10-26: swipes landed only in a band at the pane's middle, truck and emulator alike): the
 * pager's drag detector verifies on that same Final pass that nobody consumed the first moves, and
 * the backstop had — so a slow-starting drag was cancelled before it reached touch slop, and only a
 * swipe fast enough to pass slop on its first move ever landed.
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
    // The deck too: the card the driver swiped to is kept across a Dock hide and a call.
    val deck = rememberDeck(carlinkManager, media, session = rect != null)
    val r = rect
    if (r != null) {
        Box(modifier = modifier) {
            val fraction = r.paneFraction
            if (fraction > 0f) {
                PaneHost(
                    carlinkManager,
                    art = art,
                    deck = deck,
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

/**
 * The deck's cards and the one up, moved by the driver's swipes and by four events besides: a track
 * or route that comes or goes ([PaneDeck.after]), music starting ([PaneDeck.onMusicStart]), the title
 * tap that puts a card's app in front in CarPlay ([PaneDeck.beside]), and a tap on CarPlay's Dock
 * after that ([PaneDeck.onLeft]).
 */
@Composable
private fun rememberDeck(
    carlinkManager: CarlinkManager,
    media: NowPlayingState,
    session: Boolean,
): Deck {
    val nav by NavigationStateManager.state.collectAsState()
    val vehicle by VehicleData.available.collectAsState()
    val pages = PaneDeck.pages(navigating = nav.isActive, hasTrack = media.hasTrack, hasVehicle = vehicle)
    var shown by remember { mutableStateOf(PaneDeck.default(pages)) }
    var dealt by remember { mutableStateOf(pages) }
    // Now Playing put in front by the pane's title, and the card the pane stepped to; null when it is
    // not known to be in front. Forgotten with the session.
    var aside by remember { mutableStateOf<Aside?>(null) }
    LaunchedEffect(pages) {
        if (pages != dealt) {
            shown = PaneDeck.after(dealt, pages, shown)
            dealt = pages
        }
    }
    // Music starting (play after a pause, or already playing when the pane appears) brings the music
    // card up from the clock; a new track's card is dealt by `after` above. Not while Now Playing is in
    // front: CarPlay shows the music then, and a play pressed there would put it on both sides.
    val playing = media.playing
    LaunchedEffect(playing) {
        if (playing && aside == null) {
            val next = PaneDeck.onMusicStart(pages, shown)
            if (next != shown) {
                logInfo("[UI_ACTION] Pane: music started, showing $next", tag = "UI")
                shown = next
            }
        }
    }
    val dockTaps by carlinkManager.dockTaps.collectAsState()
    val dealtNow by rememberUpdatedState(pages)
    LaunchedEffect(dockTaps) {
        val left = aside ?: return@LaunchedEffect
        // The Dock also holds CarPlay's own full-screen button, which leaves the app in front where it
        // is: a tap that starts a resize is not a leave. The wait also lets CarPlay's switch land first.
        val area = carlinkManager.viewArea.value
        delay(LEAVE_SETTLE_MS)
        if (carlinkManager.viewArea.value != area) return@LaunchedEffect
        aside = null
        val next = PaneDeck.onLeft(left, shown, dealtNow)
        if (next != shown) {
            logInfo("[UI_ACTION] Pane: CarPlay Dock tapped, ${left.app} left, showing $next", tag = "UI")
            shown = next
        }
    }
    LaunchedEffect(session) { if (!session) aside = null }
    return Deck(pages, shown, onShown = { shown = it }) { app ->
        val to = PaneDeck.beside(app, pages)
        aside = if (app == PaneCard.MEDIA) Aside(app, to) else null
        shown = to
    }
}

/** The cards dealt, the one up, where a swipe reports a new one, and where a title tap reports the card whose app it put in front. */
private class Deck(
    val pages: List<PaneCard>,
    val shown: PaneCard,
    val onShown: (PaneCard) -> Unit,
    val onInFront: (PaneCard) -> Unit,
)

/** The pane itself: the backdrop, a header naming the card with the full-screen button, then the card. */
@Composable
private fun PaneHost(
    carlinkManager: CarlinkManager,
    /** The current cover, kept by the caller; shown only behind the music card. */
    art: ImageBitmap?,
    deck: Deck,
    modifier: Modifier = Modifier,
) {
    val call by carlinkManager.callPresentationFlow.collectAsState()
    val media by carlinkManager.nowPlaying.collectAsState()
    val nav by NavigationStateManager.state.collectAsState()
    val showCall = call != CallRoster.Presentation.None
    val card = if (showCall) null else deck.shown
    val showMedia = card == PaneCard.MEDIA
    // A call, the route and the clock take the pane with its plain background; the cover waits for the music's return.
    val art = if (showMedia) art else null
    val look = paneLook(art, rememberArtIsDark(art), night = isSystemInDarkTheme())
    val title = paneTitle(card, media.info.appName)
    val onTitle = titleTap(card, deck, carlinkManager)
    // Where the music card's title starts (root coordinates): the frost on the art begins just above it.
    var wordsTop by remember { mutableFloatStateOf(Float.NaN) }
    Surface(
        modifier =
            modifier
                // Rounded like CarPlay's own pane beside it.
                .clip(RoundedCornerShape(PANE_CORNER)),
        color = look.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            MediaBackdrop(art, background = look.background, wordsTopInRoot = wordsTop, modifier = Modifier.fillMaxSize())
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
                    val header: @Composable () -> Unit = { PaneHeader(title = title, onTitle = onTitle, onExpand = { carPlayFullScreen(carlinkManager) }) }
                    if (showCall) {
                        header()
                        CallCard(presentation = call, onButton = { carlinkManager.sendTelephony(it) }, modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
                    } else {
                        PaneDeckPager(pages = deck.pages, shown = deck.shown, onShown = deck.onShown, header = header, modifier = Modifier.fillMaxSize()) { which, m ->
                            when (which) {
                                PaneCard.DIRECTIONS -> DirectionsCard(state = nav, modifier = m)
                                PaneCard.MEDIA -> MediaCard(state = media, onControl = carlinkManager::mediaControl, modifier = m, onWordsTop = { wordsTop = it })
                                PaneCard.CLOCK -> ClockCard(modifier = m)
                                PaneCard.VEHICLE -> VehicleCard(modifier = m)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The pane's full-screen button: CarPlay back to the whole frame. */
private fun carPlayFullScreen(carlinkManager: CarlinkManager) {
    logInfo("[UI_ACTION] Pane: CarPlay full screen", tag = "UI")
    carlinkManager.requestViewArea(0)
}

/** The pane's background, the colour of the words and buttons over it, and the scrim between art and words. */
private class PaneLook(
    val background: Color,
    val content: Color,
    val scrim: Color,
)

/**
 * Without art the pane is white by day and near-black by night — the car's day/night, the same
 * signal CarPlay's own night mode follows (owner, 10-26). Over art the words are white or near-black
 * for the cover, whatever the time of day.
 */
private fun paneLook(
    art: ImageBitmap?,
    dark: Boolean,
    night: Boolean,
): PaneLook =
    when {
        art == null && night -> PaneLook(NIGHT_PANE, Color.White, Color.Black)
        art == null -> PaneLook(Color.White, INK, Color.White)
        dark -> PaneLook(NIGHT_PANE, Color.White, Color.Black)
        else -> PaneLook(Color.White, INK, Color.White)
    }

/**
 * What a tap on the header's title does: it puts the card's app in front in CarPlay (the box's
 * `requestUI` URL), and the pane moves on to what CarPlay is then not showing. Nothing for the clock
 * or a call. Which app is in front is otherwise unknown to the app, so this tap is its one tell.
 */
private fun titleTap(
    card: PaneCard?,
    deck: Deck,
    carlinkManager: CarlinkManager,
): (() -> Unit)? {
    val url =
        when (card) {
            PaneCard.MEDIA -> CarPlayUrls.NOW_PLAYING
            PaneCard.DIRECTIONS -> CarPlayUrls.MAPS
            else -> return null
        }
    return {
        carlinkManager.openCarPlayApp(url)
        deck.onInFront(deck.shown)
    }
}

/** The header's word for the card: the phone during a call (null card), the app playing (its own name), the route, this app, or the truck. */
private fun paneTitle(
    card: PaneCard?,
    appName: String?,
): String =
    when (card) {
        null -> "Phone"
        PaneCard.MEDIA -> appName?.takeIf { it.isNotBlank() } ?: "Music"
        PaneCard.DIRECTIONS -> "Directions"
        PaneCard.CLOCK -> "Carlink3"
        PaneCard.VEHICLE -> "Vehicle"
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

/** The pane at night: the head unit's own near-black, from the design mockup. */
private val NIGHT_PANE = Color(0xFF0B0D10)

/** CarPlay's corner radius on the truck's panel, matched by eye with a slider on 10-25 and then fixed. */
private val PANE_CORNER = 52.dp
private const val SCRIM_TOP = 0.45f
private const val SCRIM_BOTTOM = 0.75f
private const val HEADER_ALPHA = 0.85f
private const val TAB_ALPHA = 0.7f

/** How long after a Dock tap the pane waits before taking it as a leave: past CarPlay's 500 ms resize start. */
private const val LEAVE_SETTLE_MS = 800L
