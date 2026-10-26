package com.carlink.ui.pane

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.carlink.media.NowPlayingInfo
import com.carlink.media.NowPlayingState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pane's swipe surface, measured: a horizontal swipe at ANY height of the card must change the
 * card, and no pane touch may reach the view below. Built like [PaneHost] (the clipped Surface, the
 * padded Column, the pager with the real music card) over a touch-counting View standing in for the
 * CarPlay SurfaceView, at the owner's 25 % pane size (480 x 768 dp).
 *
 * Owner, 10-26: swipes landed only in a band at the pane's vertical middle, truck and emulator alike.
 * The cause was a Final-pass "backstop" consuming every unclaimed change: this sweep missed at every
 * height with it and at none without it, with no touch reaching the view either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaneSwipeBandTest {
    @get:Rule
    val compose = createComposeRule()

    private val viewTouches = AtomicInteger(0)

    @Test
    fun a_swipe_at_every_height_of_the_card_changes_it() {
        assertEquals("heights (fraction of the pane) where the swipe missed", emptyList<Float>(), sweep())
    }

    @Test
    fun no_pane_touch_reaches_the_view_below() {
        sweep()
        compose.onNodeWithTag(PANE).performTouchInput { click(Offset(width * TAP_X, height * TAP_Y)) }
        compose.waitForIdle()
        assertEquals("touches that reached the view under the pane", 0, viewTouches.get())
    }

    /** Swipes left once at each of [HEIGHTS]; returns the heights at which the card did not change. */
    private fun sweep(): List<Float> {
        val shown = mutableStateOf(PaneCard.MEDIA)
        compose.setContent { Bench(shown.value) { shown.value = it } }
        val missed = mutableListOf<Float>()
        for (fraction in HEIGHTS) {
            compose.runOnIdle { shown.value = PaneCard.MEDIA }
            compose.waitForIdle()
            compose.onNodeWithTag(PANE).performTouchInput {
                val y = height * fraction
                swipe(start = Offset(width * FROM, y), end = Offset(width * TO, y), durationMillis = SWIPE_MS)
            }
            compose.mainClock.advanceTimeBy(SETTLE_MS)
            compose.waitForIdle()
            if (shown.value == PaneCard.MEDIA) missed += fraction
        }
        return missed
    }

    @Composable
    private fun Bench(
        shown: PaneCard,
        onShown: (PaneCard) -> Unit,
    ) {
        MaterialTheme {
            Box(modifier = Modifier.size(PANE_W, PANE_H)) {
                // The CarPlay SurfaceView: a View that takes, and counts, every touch it is given.
                AndroidView(
                    factory = { ctx ->
                        View(ctx).apply {
                            setOnTouchListener { _, _ ->
                                viewTouches.incrementAndGet()
                                true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Surface(modifier = Modifier.fillMaxSize().testTag(PANE).clip(RoundedCornerShape(52.dp))) {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                        PaneDeckPager(
                            pages = listOf(PaneCard.DIRECTIONS, PaneCard.MEDIA, PaneCard.CLOCK),
                            shown = shown,
                            onShown = onShown,
                            header = { Box(modifier = Modifier.fillMaxWidth().height(56.dp)) },
                            modifier = Modifier.fillMaxSize(),
                        ) { which, m ->
                            when (which) {
                                PaneCard.MEDIA -> MediaCard(state = TRACK, onControl = {}, modifier = m)
                                else -> ClockCard(modifier = m)
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val PANE = "pane"
        val PANE_W = 480.dp
        val PANE_H = 768.dp
        val HEIGHTS = listOf(0.05f, 0.12f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 0.97f)
        const val FROM = 0.85f
        const val TO = 0.25f
        const val TAP_X = 0.5f
        const val TAP_Y = 0.3f
        const val SWIPE_MS = 400L
        const val SETTLE_MS = 3_000L
        val TRACK =
            NowPlayingState(
                NowPlayingInfo(title = "Midnight City", artist = "M83", album = "Hurry Up, We're Dreaming", appName = "Music", durationMs = 243_000L),
                playing = true,
                positionMs = 60_000L,
                positionAtMs = 1L,
            )
    }
}
