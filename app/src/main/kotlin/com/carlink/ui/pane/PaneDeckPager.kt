package com.carlink.ui.pane

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.carlink.logging.logInfo

/**
 * The deck's cards side by side, one in view, a swipe away from the next ([PaneDeck] says which
 * cards and which one is up). The pager follows [shown]: a slide when the card changed, a plain jump
 * when only the deck was reshuffled under it (a route or a track that came or went). A swipe that
 * settles on another card reports it through [onShown]. Dots under the cards show the position
 * while there is more than one.
 */
@Composable
internal fun PaneDeckPager(
    pages: List<PaneCard>,
    shown: PaneCard,
    onShown: (PaneCard) -> Unit,
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
    LaunchedEffect(pagerState.settledPage) {
        val settled = pages.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
        if (settled != shown) {
            logInfo("[UI_ACTION] Pane: swiped to $settled", tag = "UI")
            onShown(settled)
        }
    }
    Column(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            key = { pages[it] },
            pageSpacing = 24.dp,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            val which = pages.getOrNull(page) ?: return@HorizontalPager
            card(which, Modifier.fillMaxSize())
        }
        if (pages.size > 1) PageDots(count = pages.size, current = pagerState.currentPage)
    }
}

@Composable
private fun PageDots(
    count: Int,
    current: Int,
) {
    val content = LocalContentColor.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
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
