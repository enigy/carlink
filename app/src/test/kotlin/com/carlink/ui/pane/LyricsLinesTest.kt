package com.carlink.ui.pane

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.carlink.media.LyricLine
import com.carlink.media.NowPlayingInfo
import com.carlink.media.NowPlayingState
import com.carlink.media.SyncedLyrics
import com.carlink.ui.settings.MusicStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The music card's lyrics: the window of lines around the sung one, and the drag that sizes it (owner, 10-27). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsLinesTest {
    @get:Rule
    val compose = createComposeRule()

    private val music get() = MusicStore.get(ApplicationProvider.getApplicationContext())

    // Six invented lines a second apart; paused at 3.5 s, so "line 3" is sung.
    private val lyrics = SyncedLyrics((0..5).map { LyricLine(it * 1_000L, "line $it") })
    private val paused =
        NowPlayingState(NowPlayingInfo(title = "Song", artist = "Band", album = null, appName = "Music"), playing = false, positionMs = 3_500L, positionAtMs = 1L)

    @Before
    fun setUp() = music.setLyricLines(3)

    private fun show() =
        compose.setContent {
            MaterialTheme { Box(modifier = Modifier.size(480.dp, 600.dp)) { LyricsLines(lyrics, paused, modifier = Modifier.fillMaxWidth().testTag(LYRICS)) } }
        }

    @Test
    fun the_count_splits_around_the_sung_line_with_the_odd_one_ahead() {
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 1, 1 to 2, 2 to 2), (1..5).map { LyricWindow.behind(it) to LyricWindow.ahead(it) })
    }

    @Test
    fun three_lines_by_default_one_before_one_after() {
        show()
        listOf("line 2", "line 3", "line 4").forEach { compose.onNodeWithText(it).assertExists() }
        listOf("line 1", "line 5").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun a_drag_up_shows_more_lines_and_a_drag_down_fewer() {
        show()
        compose.onNodeWithTag(LYRICS).performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals("up to two before and two after", MusicStore.MAX_LYRIC_LINES, music.lyricLinesFlow.value)
        listOf("line 1", "line 2", "line 3", "line 4", "line 5").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithTag(LYRICS).performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals("down to the sung line alone", MusicStore.MIN_LYRIC_LINES, music.lyricLinesFlow.value)
        // The lines that left dissolve before they go.
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        compose.onNodeWithText("line 3").assertExists()
        compose.onNodeWithText("line 4").assertDoesNotExist()
    }

    @Test
    fun a_line_that_wraps_when_sung_is_already_wrapped_as_the_next_line() {
        // Owner, 10-28: a long line reflowing from one line to two as it took the sung place was rough.
        val long = "Every mile a little slower than the one before"
        val wrapping = SyncedLyrics((0..5).map { LyricLine(it * 1_000L, if (it == 4) long else "line $it") })
        compose.setContent {
            MaterialTheme { Box(modifier = Modifier.size(480.dp, 600.dp)) { LyricsLines(wrapping, paused, modifier = Modifier.fillMaxWidth()) } }
        }
        compose.waitForIdle()
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(long).fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals("laid out as when sung, though line 3 is sung", 2, layouts.first().lineCount)
    }

    @Test
    fun turning_lyrics_off_folds_them_away_rather_than_dropping_them() {
        val on = mutableStateOf<SyncedLyrics?>(lyrics)
        compose.setContent {
            MaterialTheme { Box(modifier = Modifier.size(480.dp, 600.dp)) { LyricsReveal(on.value, paused) } }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.onNodeWithText("line 3").assertExists()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { on.value = null }
        compose.mainClock.advanceTimeBy(FRAME_MS * 3)
        compose.onNodeWithText("line 3").assertExists()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.onNodeWithText("line 3").assertDoesNotExist()
    }

    private companion object {
        const val SETTLE_MS = 2_000L
        const val FRAME_MS = 16L
        const val LYRICS = "lyrics"
    }
}
