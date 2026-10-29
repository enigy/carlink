package com.carlink.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.carlink.CarlinkManager
import com.carlink.logging.logWarn
import com.carlink.ocbm.PhoneSetup
import com.carlink.ocbm.PhoneSetupPhase
import kotlinx.coroutines.delay

// Setting up an iPhone on the connecting screen (29 Oct bench, plan page v11 "Cases to plan for"; the
// mockup's "Set up the iPhone" step): when the adapter has no phone paired, lead with pairing one
// rather than "Waiting for phone"; when phones are paired but none comes, offer to pair a new one.

/** The connecting screen's pairing panel, or [otherwise] (the status words) when there is none. */
@Composable
internal fun PhoneSetupPanelOr(
    carlinkManager: CarlinkManager,
    otherwise: @Composable () -> Unit,
) {
    val setup = carlinkManager.phoneSetup
    val phase by setup.phase.collectAsState()
    when (val p = phase) {
        PhoneSetupPhase.None -> otherwise()
        is PhoneSetupPhase.Waiting ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                otherwise()
                WaitingAction(p, setup)
            }
        is PhoneSetupPhase.Pairing -> OverlayPanel(pairingPanel(p, secondsLeft(p.endsAtMs), setup))
        else -> OverlayPanel(setupPanel(p, setup))
    }
}

/** The prompt, and what can go wrong with it. */
private fun setupPanel(
    phase: PhoneSetupPhase,
    setup: PhoneSetup,
): PanelContent {
    val pair = PanelAction("Pair iPhone", onClick = { pairTapped(setup) })
    val later = PanelAction("Later", PanelActionLook.QUIET, setup::later)
    return when (phase) {
        is PhoneSetupPhase.Prompt ->
            PanelContent(
                meta = "${phase.name} · ${if (phase.newPhone) "pair a new iPhone" else "no iPhone paired yet"}",
                title = "Pair your iPhone",
                body = pairSteps(phase.name),
                actions = listOf(pair, later),
            )
        is PhoneSetupPhase.NotPaired -> PanelContent(meta = phase.name, title = phase.title, body = phase.body, actions = listOf(pair.copy(label = "Try again"), later))
        is PhoneSetupPhase.BluetoothDown ->
            PanelContent(
                meta = "${phase.name} · pairing refused, status ${phase.status}",
                title = "The adapter's Bluetooth didn't start",
                body = "Unplug the adapter, take any USB stick out of it, and plug it back in. Then pair the iPhone again.",
                actions = listOf(PanelAction("OK", onClick = setup::later)),
            )
        // Pairing, Waiting and None have their own views.
        else -> PanelContent(title = "", body = "")
    }
}

/** The window is open: what to pick on the iPhone, the time left, and how far the phone got. */
private fun pairingPanel(
    phase: PhoneSetupPhase.Pairing,
    secondsLeft: Long?,
    setup: PhoneSetup,
): PanelContent {
    val name = phase.name
    val progress = listOfNotNull(phase.progress, phase.code?.let { "Check that the iPhone shows $it." })
    return PanelContent(
        meta = secondsLeft?.let { "Pairing open for %d:%02d".format(it / SECONDS_PER_MINUTE, it % SECONDS_PER_MINUTE) } ?: "Pairing is open",
        title = "Pick $name on the iPhone",
        body =
            "On the iPhone, open Settings › General › CarPlay (or Settings › Bluetooth) and choose $name." +
                progress.joinToString("") { "\n$it" },
        busy = true,
        actions = listOf(PanelAction("Stop", PanelActionLook.QUIET, setup::stop)),
    )
}

/** Beside "Waiting for phone": a way to pair when the phone that should come never does. */
@Composable
private fun WaitingAction(
    phase: PhoneSetupPhase.Waiting,
    setup: PhoneSetup,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (phase.noPhone) "No iPhone is paired with ${phase.name} yet." else "If you removed ${phase.name} from the iPhone, pair it again.",
            style = MaterialTheme.typography.bodyLarge,
            color = WaitingText,
        )
        Spacer(modifier = Modifier.height(12.dp))
        PanelButton(PanelAction(if (phase.noPhone) "Pair iPhone" else "Pair a new iPhone", PanelActionLook.QUIET, setup::pairNew))
    }
}

/** "Pair iPhone" here is Settings' "Add phone": the same call, logged the same way. */
private fun pairTapped(setup: PhoneSetup) {
    logWarn("[UI_ACTION] Pairing window: add phone", tag = "UI")
    setup.pair()
}

/** Step 0 is the stick, which the one-file flow leaves in the adapter; then forget the adapter, then pair. */
internal fun pairSteps(name: String): String =
    "1. $STICK_OUT\n" +
        "2. On the iPhone, open Settings › Bluetooth. If $name is listed, tap (i) › Forget This Device. Also remove the car under " +
        "Settings › General › CarPlay if it is there.\n" +
        "3. Tap Pair iPhone, then pick $name on the iPhone."

/** What the one-file flow's stick needs said, on the welcome and the pairing screens. */
internal const val STICK_OUT =
    "Take the USB stick out of the adapter and keep it. It holds the adapter's flash backup, including its Wi-Fi password " +
        "and Bluetooth keys, so keep it private."

/** Seconds until [endsAtMs] (elapsedRealtime), ticking; null with no end. */
@Composable
private fun secondsLeft(endsAtMs: Long?): Long? {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(endsAtMs) {
        while (endsAtMs != null && endsAtMs > SystemClock.elapsedRealtime()) {
            now = SystemClock.elapsedRealtime()
            delay(TICK_MS)
        }
        now = SystemClock.elapsedRealtime()
    }
    return endsAtMs?.let { ((it - now).coerceAtLeast(0) + TICK_MS - 1) / TICK_MS }
}

private val WaitingText = Color(0xFFDDE4E5)
private const val TICK_MS = 1_000L
private const val SECONDS_PER_MINUTE = 60
