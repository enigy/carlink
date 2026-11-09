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
import com.carlink.ocbm.PhoneSetup
import kotlinx.coroutines.delay

/** How long "Add phone" opens the adapter's pairing window for. */
internal const val PAIR_WINDOW_SECONDS = PhoneSetup.WINDOW_SECONDS

/** If the box hasn't reported the window open this long after the tap, say so. */
internal const val OPEN_CONFIRM_MS = 5_000L
private const val TICK_MS = 1_000L
private const val SECONDS_PER_MINUTE = 60

/** What the pairing card shows: the window [open] with [secondsLeft], or not, and whether a tap went [unanswered]. */
internal data class PairCardView(
    val open: Boolean,
    val secondsLeft: Long,
    val unanswered: Boolean,
    /** Something can still change by itself: the countdown, a tap the box hasn't answered, a Stop it hasn't followed. */
    val ticking: Boolean,
)

/**
 * The card follows the box's BH_PAIR_WINDOW ([boxSaysOpen]). On the 9 Nov bench an open that btd refused still counted
 * down for two minutes, so the next tap was Stop. A tap ([tappedAt], 0 for none) shows the window open until the box
 * answers: for [OPEN_CONFIRM_MS], or while the adapter's Bluetooth starts and Carlink asks again; after that it is
 * [PairCardView.unanswered]. A Stop ([stoppedAt]) shows it closed at once, and open again if the box still says so
 * [OPEN_CONFIRM_MS] later. [endsAt] is the countdown's end, 0 when there is none yet.
 */
@Suppress("LongParameterList") // the box's word, the driver's two taps and the clock
internal fun pairCardView(
    boxSaysOpen: Boolean,
    bluetoothStarting: Boolean,
    tappedAt: Long,
    stoppedAt: Long,
    endsAt: Long,
    now: Long,
): PairCardView {
    val waiting = tappedAt != 0L && !boxSaysOpen && (bluetoothStarting || now - tappedAt <= OPEN_CONFIRM_MS)
    val stopping = stoppedAt != 0L && boxSaysOpen && now - stoppedAt <= OPEN_CONFIRM_MS
    val open = (boxSaysOpen && !stopping) || waiting
    val ends = if (endsAt != 0L) endsAt else now + PAIR_WINDOW_SECONDS * TICK_MS
    return PairCardView(
        open = open,
        secondsLeft = if (open) (ends - now).coerceAtLeast(0) / TICK_MS else 0L,
        unanswered = tappedAt != 0L && !boxSaysOpen && !waiting,
        ticking = open || stopping,
    )
}

/**
 * "Add phone" for a box that gates Bluetooth pairing (security plan 2026-10-21 §3): new phones can
 * pair only while the window is open, and only paired phones are given the adapter's Wi-Fi details.
 *
 * Tapping opens the window for [PAIR_WINDOW_SECONDS]. The card shows what the box reports ([pairCardView]): it counts
 * down while the box says the window is open (it closes early once a new phone has paired), and says so when a tap
 * isn't answered.
 */
@Composable
internal fun PairPhoneCard(
    adapterName: String,
    boxSaysOpen: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    // The adapter's Bluetooth is still starting, and Carlink asks again until it answers (PhoneSetupPhase.BluetoothStarting).
    bluetoothStarting: Boolean = false,
) {
    val card = remember { PairCardClock() }
    LaunchedEffect(boxSaysOpen) { card.boxSays(boxSaysOpen) }
    LaunchedEffect(boxSaysOpen, bluetoothStarting, card.tappedAt, card.stoppedAt) {
        while (true) {
            card.now = SystemClock.elapsedRealtime()
            if (!card.view(boxSaysOpen, bluetoothStarting).ticking) break
            delay(TICK_MS)
        }
    }
    val view = card.view(boxSaysOpen, bluetoothStarting)
    val counting = view.open

    PairCardFrame {
        Icon(
            imageVector = if (counting) Icons.AutoMirrored.Filled.BluetoothSearching else Icons.Default.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(pairText(counting, adapterName, view.secondsLeft), style = SettingsStyle.Body)
            if (bluetoothStarting) {
                Text("The adapter's Bluetooth is starting. Pairing opens as soon as it's ready.", style = SettingsStyle.RowSubtitle)
            } else if (view.unanswered) {
                Text("The adapter hasn't opened pairing. Check it is connected, then try again.", style = SettingsStyle.RowSubtitle, color = SettingsStyle.ErrorText)
            }
        }
        RowAction(
            label = if (counting) "Stop" else "Add phone",
            onClick = {
                if (counting) {
                    logWarn("[UI_ACTION] Pairing window: stop", tag = "UI")
                    onClose()
                    card.tapStop()
                } else {
                    logWarn("[UI_ACTION] Pairing window: add phone", tag = "UI")
                    onOpen()
                    card.tapOpen()
                }
            },
        )
    }
}

/** The card's own memory for [pairCardView]: the driver's last tap and Stop, the countdown's end, and the clock. */
private class PairCardClock {
    var tappedAt by mutableLongStateOf(0L)
    var stoppedAt by mutableLongStateOf(0L)
    var endsAt by mutableLongStateOf(0L)
    var now by mutableLongStateOf(SystemClock.elapsedRealtime())

    fun view(
        boxSaysOpen: Boolean,
        bluetoothStarting: Boolean,
    ): PairCardView = pairCardView(boxSaysOpen, bluetoothStarting, tappedAt, stoppedAt, endsAt, now)

    fun tapOpen() {
        val at = SystemClock.elapsedRealtime()
        tappedAt = at
        stoppedAt = 0L
        endsAt = at + PAIR_WINDOW_SECONDS * TICK_MS
    }

    fun tapStop() {
        tappedAt = 0L
        stoppedAt = SystemClock.elapsedRealtime()
    }

    fun boxSays(open: Boolean) {
        if (open) {
            // Answered: the tap's window, or one opened elsewhere (the connecting screen, or the adapter itself).
            if (endsAt == 0L) endsAt = SystemClock.elapsedRealtime() + PAIR_WINDOW_SECONDS * TICK_MS
            tappedAt = 0L
        } else {
            // The box closed it: timeout, a phone paired, or Stop.
            endsAt = 0L
            stoppedAt = 0L
        }
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
