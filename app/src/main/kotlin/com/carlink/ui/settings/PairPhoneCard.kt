package com.carlink.ui.settings

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.carlink.logging.logWarn
import kotlinx.coroutines.delay

/** How long "Add phone" opens the adapter's pairing window for. */
internal const val PAIR_WINDOW_SECONDS = 120

/** If the box hasn't reported the window open this long after the tap, say so. */
private const val OPEN_CONFIRM_MS = 5_000L
private const val TICK_MS = 1_000L
private const val SECONDS_PER_MINUTE = 60

/**
 * "Add phone" for a box that gates Bluetooth pairing (security plan 2026-10-21 §3): new phones can
 * pair only while the window is open, and only paired phones are given the adapter's Wi-Fi details.
 *
 * Tapping opens the window for [PAIR_WINDOW_SECONDS]; the card counts down until the box reports the
 * window closed (it closes early once a new phone has paired) or the time runs out.
 */
@Composable
internal fun PairPhoneCard(
    adapterName: String,
    boxSaysOpen: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var endsAt by remember { mutableLongStateOf(0L) }
    var openedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var sawOpen by remember { mutableStateOf(false) }
    val counting = endsAt > now

    LaunchedEffect(endsAt) {
        while (endsAt > SystemClock.elapsedRealtime()) {
            now = SystemClock.elapsedRealtime()
            delay(TICK_MS)
        }
        now = SystemClock.elapsedRealtime()
    }
    LaunchedEffect(boxSaysOpen) {
        if (boxSaysOpen) {
            sawOpen = true
        } else if (sawOpen) {
            // The box closed it: timeout, a phone paired, or Stop.
            sawOpen = false
            endsAt = 0L
        }
    }
    val noAnswer = counting && !sawOpen && now - openedAt > OPEN_CONFIRM_MS

    PairCardFrame(modifier) {
        Icon(
            imageVector = if (counting) Icons.AutoMirrored.Filled.BluetoothSearching else Icons.Default.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(pairText(counting, adapterName, (endsAt - now).coerceAtLeast(0) / TICK_MS), style = SettingsStyle.Body)
            if (noAnswer) {
                Text("The adapter hasn't opened pairing. Check it is connected, then try again.", style = SettingsStyle.RowSubtitle, color = SettingsStyle.ErrorText)
            }
        }
        RowAction(
            label = if (counting) "Stop" else "Add phone",
            onClick = {
                if (counting) {
                    logWarn("[UI_ACTION] Pairing window: stop", tag = "UI")
                    onClose()
                    endsAt = 0L
                } else {
                    logWarn("[UI_ACTION] Pairing window: add phone", tag = "UI")
                    onOpen()
                    openedAt = SystemClock.elapsedRealtime()
                    endsAt = openedAt + PAIR_WINDOW_SECONDS * TICK_MS
                }
            },
        )
    }
}

@Composable
private fun PairCardFrame(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SettingsStyle.GroupShape,
        color = colors.surface.copy(alpha = 0f),
        border = BorderStroke(2.dp, colors.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
}

@Composable
private fun pairText(
    counting: Boolean,
    adapterName: String,
    secondsLeft: Long,
) = buildAnnotatedString {
    val accent = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    if (counting) {
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
            append("Pairing open for %d:%02d. ".format(secondsLeft / SECONDS_PER_MINUTE, secondsLeft % SECONDS_PER_MINUTE))
        }
        append("On the iPhone, open Settings › General › CarPlay and choose ")
        withStyle(accent) { append(adapterName) }
        append(".")
    } else {
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append("Add a phone: ") }
        append("tap Add phone, then on the iPhone open Settings › General › CarPlay and choose ")
        withStyle(accent) { append(adapterName) }
        append(". Or plug it into the adapter's USB port.")
    }
}
