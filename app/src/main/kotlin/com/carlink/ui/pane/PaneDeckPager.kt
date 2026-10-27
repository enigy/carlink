package com.carlink.ui.pane

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.carlink.logging.logInfo
import kotlinx.coroutines.flow.merge

/**
 * The deck's cards side by side, one in view, a swipe away from the next ([PaneDeck] says which
 * cards and which one is up). The pager follows [shown]: a slide when the card changed, a plain jump
 * when only the deck was reshuffled under it (a route or a track that came or went). A swipe that
 * settles on another card reports it through [onShown]. Dots under the cards show the position
 * while there is more than one.
 *
 * The [header] and the dots sit outside the pager but swipe it all the same: each is a scrollable on
 * the pager's own state with the pager's own snapping fling, so a drag anywhere on the pane moves
 * the deck, bar the two buttons (owner, 10-26: swipes landed only in the middle of the pane).
 */
@Suppress("LongParameterList") // the deck, where it reports, its header and its cards
@Composable
internal fun PaneDeckPager(
    pages: List<PaneCard>,
    shown: PaneCard,
    onShown: (PaneCard) -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    card: @Composable (PaneCard, Modifier) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = pages.indexOf(shown).coerceAtLeast(0)) { pages.size }
    var followed by remember { mutableStateOf(shown) }
    LaunchedEffect(pages, shown) {
        val target = pages.indexOf(shown).coerceAtLeast(0)
        val changed = shown != followed
        followed = shown
        if (pagerState.currentPage == target) return@LaunchedEffect
        if (changed) pagerState.animateScrollToPage(target) else pagerState.scrollToPage(target)
    }
    // Only a settle that follows the driver's drag is a swipe. A deck reshuffle moves the settled page
    // too, and reporting that overrode the card the deck chose (owner's log 10-26: "swiped to CLOCK"
    // 85 ms after the first track came, with no finger on the pane).
    val drags = remember { MutableInteractionSource() }
    val watch = rememberDragWatch(pagerState, drags)
    LaunchedEffect(pagerState.settledPage) {
        val settled = pages.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
        if (settled != shown && watch.justDragged(SystemClock.uptimeMillis())) {
            logInfo("[UI_ACTION] Pane: swiped to $settled", tag = "UI")
            onShown(settled)
        }
    }
    // The same direction convention the pager uses for this layout direction, else a drag on the
    // header would move the deck the wrong way.
    val reverse = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, Orientation.Horizontal, reverseScrolling = false)
    // A short, slow swipe counts. The pager's default snaps BACK unless the drag covered half the
    // page or left fast (about 400 dp/s); in the car swipes are short and deliberate, so most of them
    // snapped back and read as "nothing happened" (owner, 10-26, release build). A quarter inch
    // ([SWIPE_DISTANCE]) now changes the card, as the head unit's own pages do. The pager wants it as
    // a fraction of the page, so it is taken from the measured page width (a guess before the first
    // layout); key(): the fling behaviour captures the fraction when it is made.
    val pageSizePx = pagerState.layoutInfo.pageSize
    val swipePx = with(LocalDensity.current) { SWIPE_DISTANCE.toPx() }
    val threshold = if (pageSizePx > 0) (swipePx / pageSizePx).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD) else GUESS_THRESHOLD
    val fling = key(threshold) { PagerDefaults.flingBehavior(state = pagerState, snapPositionalThreshold = threshold) }
    val swipesToo = Modifier.scrollable(pagerState, Orientation.Horizontal, reverseDirection = reverse, flingBehavior = fling, interactionSource = drags)
    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth().then(swipesToo)) { header() }
        // No page keys: a keyed pager keeps the card in view when one is added before it (the first
        // track's music card before the clock), moving the page under the effect above that has just
        // found it already on the deck's card. By index, a reshuffle changes what the page holds and
        // the effect scrolls to the deck's card.
        HorizontalPager(
            state = pagerState,
            pageSpacing = 24.dp,
            flingBehavior = fling,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
        ) { page ->
            val which = pages.getOrNull(page) ?: return@HorizontalPager
            card(which, Modifier.fillMaxSize())
        }
        if (pages.size > 1) PageDots(count = pages.size, current = pagerState.currentPage, modifier = swipesToo)
    }
}

/** When the driver last had a finger on the deck: the pager's own drags, and the header's and dots' ([PaneDeckPager]'s `drags`). */
private class DragWatch {
    @Volatile var open = false

    @Volatile var endedAt = -1L

    /** A settle now follows the driver's drag: one is under way, or ended under [SWIPE_SETTLE_MS] ago. */
    fun justDragged(nowMs: Long): Boolean = open || (endedAt >= 0 && nowMs - endedAt < SWIPE_SETTLE_MS)
}

@Composable
private fun rememberDragWatch(
    pagerState: PagerState,
    drags: InteractionSource,
): DragWatch {
    val watch = remember { DragWatch() }
    LaunchedEffect(pagerState, drags) {
        merge(pagerState.interactionSource.interactions, drags.interactions).collect {
            when (it) {
                is DragInteraction.Start -> watch.open = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    watch.open = false
                    watch.endedAt = SystemClock.uptimeMillis()
                }
            }
        }
    }
    return watch
}

@Composable
private fun PageDots(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
) {
    val content = LocalContentColor.current
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            Box(
                modifier =
                    Modifier
                        .size(DOT)
                        .clip(CircleShape)
                        .background(content.copy(alpha = if (i == current) DOT_ON else DOT_OFF)),
            )
        }
    }
}

private val DOT = 9.dp
private const val DOT_ON = 0.9f
private const val DOT_OFF = 0.3f

/** The distance a slow drag must cover to change the card: a quarter inch (160 dp to the inch), like the head unit's own pages. */
private val SWIPE_DISTANCE = 40.dp

/** The fraction used until the pager has measured its page (a quarter inch on a typical pane). */
private const val GUESS_THRESHOLD = 0.08f
private const val MIN_THRESHOLD = 0.02f
private const val MAX_THRESHOLD = 0.5f

/** How long after a drag ends its fling may take to settle and still count as the swipe. */
private const val SWIPE_SETTLE_MS = 1_500L
