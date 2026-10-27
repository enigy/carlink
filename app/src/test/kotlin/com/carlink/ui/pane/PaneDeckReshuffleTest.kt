package com.carlink.ui.pane

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The deck changing under the pager, with no finger on it. Owner's drive log 10-26: the first track's
 * music card was dealt in front of the clock, and 85 ms later the pager reported "swiped to CLOCK",
 * overriding it; only music starting 2.6 s later brought the music back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaneDeckReshuffleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun a_track_that_arrives_on_the_clock_shows_the_music_and_reports_no_swipe() {
        val pages = mutableStateOf(listOf(PaneCard.CLOCK))
        val shown = mutableStateOf(PaneCard.CLOCK)
        val reported = mutableListOf<PaneCard>()
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(480.dp, 768.dp)) {
                    PaneDeckPager(
                        pages = pages.value,
                        shown = shown.value,
                        onShown = {
                            reported += it
                            shown.value = it
                        },
                        header = {},
                        modifier = Modifier.fillMaxSize(),
                    ) { which, m -> Box(modifier = m.testTag(which.name)) { Text(which.name) } }
                }
            }
        }
        compose.waitForIdle()
        // What SplitPane does when the first track comes: the music dealt in front, and shown.
        compose.runOnIdle {
            pages.value = listOf(PaneCard.MEDIA, PaneCard.CLOCK)
            shown.value = PaneCard.MEDIA
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        assertEquals("swipes reported with no finger on the pane", emptyList<PaneCard>(), reported)
        compose.onNodeWithTag(PaneCard.MEDIA.name).assertIsDisplayed()
    }

    private companion object {
        const val SETTLE_MS = 3_000L
    }
}
