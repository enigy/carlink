package com.carlink.ui.pane

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.callui.CallRoster
import com.carlink.callui.CallState
import com.carlink.callui.DialPad
import com.carlink.media.NowPlayingState
import com.carlink.ocbm.Ocbm
import com.carlink.ui.theme.LocalCarlinkColors
import kotlinx.coroutines.delay

/**
 * The pane's call card, shown while the iPhone reports a call ([CallRoster.Presentation]): who it
 * is, how long it has run, and the buttons — Answer / Decline while ringing; Mute, the keypad and End
 * during the call. Every button is one tap of the telephony HID ([onButton] takes the `Ocbm.TEL_*`
 * index), the same path as the call notification's actions.
 */
@Composable
internal fun CallCard(
    presentation: CallRoster.Presentation,
    onButton: (Byte) -> Unit,
    modifier: Modifier = Modifier,
) {
    val call = presentation.call ?: return
    var keypad by remember(call.key) { mutableStateOf(false) }
    // The phone does not report its mute state over iAP2, so the button remembers its own taps for
    // the call; a mute made on the phone itself is not reflected here.
    var muted by remember(call.key) { mutableStateOf(false) }
    val incoming = presentation is CallRoster.Presentation.Incoming
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.weight(1f))
        if (keypad) {
            Keypad(onKey = { onButton(DialPad.index(it)) })
        } else {
            Avatar(call)
            Text(
                call.displayLine,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 20.dp),
            )
            SecondLine(call)
        }
        StatusLine(presentation)
        Spacer(modifier = Modifier.weight(1f))
        CallButtons(
            incoming = incoming,
            keypad = keypad,
            muted = muted,
            onKeypad = { keypad = !keypad },
            onMute = {
                muted = !muted
                onButton(Ocbm.TEL_MUTE)
            },
            onButton = onButton,
        )
    }
}

private val CallRoster.Presentation.call: CallState?
    get() =
        when (this) {
            is CallRoster.Presentation.Incoming -> call
            is CallRoster.Presentation.Ongoing -> call
            CallRoster.Presentation.None -> null
        }

/** The caller's initials on a disc, or a silhouette when there is only a number. */
@Composable
private fun Avatar(call: CallState) {
    val colors = MaterialTheme.colorScheme
    val initials =
        call.displayName
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            ?.take(2)
            ?.joinToString("") { it.first().uppercaseChar().toString() }
            .orEmpty()
    Box(modifier = Modifier.size(144.dp).clip(CircleShape).background(colors.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (initials.isNotEmpty()) {
            Text(initials, style = MaterialTheme.typography.displayMedium.copy(fontSize = 56.sp), color = colors.onSurface)
        } else {
            Icon(Icons.Default.Person, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(80.dp))
        }
    }
}

/** "mobile · (555) 010-2234": the label and, when the name is on the line above, the number. */
@Composable
private fun SecondLine(call: CallState) {
    val number = call.remoteId?.takeIf { it.isNotBlank() && !call.displayName.isNullOrBlank() }
    val line = listOfNotNull(call.label?.takeIf { it.isNotBlank() }, number).joinToString(" · ")
    if (line.isNotEmpty()) {
        Text(
            line,
            style = MaterialTheme.typography.titleMedium,
            color = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Ringing, calling, on hold, or the running time of an active call, ticking on the second. */
@Composable
private fun StatusLine(presentation: CallRoster.Presentation) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(presentation) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(MILLIS_PER_SECOND)
        }
    }
    val text =
        when (presentation) {
            is CallRoster.Presentation.Incoming -> "Incoming call"
            is CallRoster.Presentation.Ongoing ->
                when (presentation.call.status) {
                    CallState.STATUS_ACTIVE -> if (presentation.sinceMs > 0L) NowPlayingState.clock(now - presentation.sinceMs) else "Connected"
                    CallState.STATUS_HELD -> "On hold"
                    else -> "Calling…"
                }
            CallRoster.Presentation.None -> ""
        }
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
}

/** The twelve keys in phone order; each tap is one DTMF digit to the phone. */
@Composable
private fun Keypad(onKey: (Char) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DialPad.KEYS.chunked(KEYPAD_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { key ->
                    PaneKey(key, onClick = { onKey(key) }, fill = colors.surfaceContainerHighest, tint = colors.onSurface)
                }
            }
        }
    }
}

@Suppress("LongParameterList") // the two states, their toggles and the HID path
@Composable
private fun CallButtons(
    incoming: Boolean,
    keypad: Boolean,
    muted: Boolean,
    onKeypad: () -> Unit,
    onMute: () -> Unit,
    onButton: (Byte) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val extra = LocalCarlinkColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        if (!incoming) {
            PaneButton(
                Icons.Default.MicOff,
                if (muted) "Unmute" else "Mute",
                onClick = onMute,
                size = 72.dp,
                fill = if (muted) colors.primaryContainer else colors.surfaceContainerHighest,
                tint = if (muted) colors.onPrimaryContainer else colors.onSurface,
            )
            PaneButton(
                Icons.Default.Dialpad,
                if (keypad) "Hide keypad" else "Keypad",
                onClick = onKeypad,
                size = 72.dp,
                fill = if (keypad) colors.primaryContainer else colors.surfaceContainerHighest,
                tint = if (keypad) colors.onPrimaryContainer else colors.onSurface,
            )
        }
        PaneButton(Icons.Default.CallEnd, if (incoming) "Decline" else "End call", onClick = { onButton(Ocbm.TEL_END) }, size = 88.dp, fill = colors.error, tint = colors.onError)
        if (incoming) {
            PaneButton(Icons.Default.Call, "Answer", onClick = { onButton(Ocbm.TEL_ANSWER) }, size = 88.dp, fill = extra.connectedTile, tint = extra.onConnectedTile)
        }
    }
}

private const val KEYPAD_COLUMNS = 3
private const val SECONDARY_ALPHA = 0.72f
