package com.carlink.ui.pane

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.carlink.media.LrcLib
import com.carlink.media.SyncedLyrics

/** What the music card shows over the title: the song's timed [lyrics], or, while LRCLIB can't be asked, why ([outage]). */
@Immutable
internal data class CardLyrics(
    val lyrics: SyncedLyrics? = null,
    val outage: LrcLib.Outage? = null,
)

/**
 * A dim line over the title while the song's lyrics can't be looked up (owner, 10-31: with LRCLIB down
 * all afternoon, a card without lyrics could have meant LRCLIB had none or never answered). It says
 * which side failed, the head unit's connection or LRCLIB, and that Carlink keeps trying; it folds away
 * like the lyrics ([LyricsReveal]) when they come or LRCLIB answers that it has none.
 */
@Composable
internal fun LyricsOutageNote(
    outage: LrcLib.Outage?,
    modifier: Modifier = Modifier,
) {
    val kept = remember { arrayOfNulls<LrcLib.Outage>(1) }
    if (outage != null) kept[0] = outage
    AnimatedVisibility(
        visible = outage != null,
        modifier = modifier,
        enter = expandVertically(tween(GLIDE_MS), expandFrom = Alignment.Bottom) + fadeIn(tween(GLIDE_MS)),
        exit = shrinkVertically(tween(GLIDE_MS), shrinkTowards = Alignment.Bottom) + fadeOut(tween(GLIDE_MS)),
        label = "lyrics outage",
    ) {
        Text(
            outageLine(outage ?: kept[0]),
            style = MaterialTheme.typography.titleMedium.scaled(WORD_SCALE),
            color = LocalContentColor.current.copy(alpha = OUTAGE_ALPHA),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = LYRICS_TO_TITLE),
        )
    }
}

/** The note's words for [outage]. */
internal fun outageLine(outage: LrcLib.Outage?): String =
    when (outage) {
        LrcLib.Outage.NO_CONNECTION -> "No internet connection for lyrics. Trying again…"
        LrcLib.Outage.NOT_ANSWERING, null -> "LRCLIB, the lyrics site, isn't working right now. Trying again…"
    }

private const val OUTAGE_ALPHA = 0.6f
