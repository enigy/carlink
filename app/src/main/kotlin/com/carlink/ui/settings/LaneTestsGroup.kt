package com.carlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.carlink.logging.logWarn
import com.carlink.navigation.LaneActivity
import com.carlink.navigation.LaneSlotCard
import java.text.DateFormat
import java.util.Date
import android.text.format.DateFormat as AndroidDateFormat

/**
 * Settings › Cluster › Lane guidance tests (design "Lane guidance tests", 2026-10-21): switches for
 * finding out whether, and how much, lane guidance fits in the turn icon GM's cluster and HUD show.
 * All off by default. Only "Ask iPhone for lanes" reaches the adapter; the rest change what Carlink3
 * draws ([com.carlink.navigation.ClusterStep]).
 */
@Composable
internal fun LaneTestsGroup(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { LaneTestStore.get(context) }
    val tests by store.flow.collectAsState()
    val asked by LaneActivity.askedThisConnection.collectAsState()
    val seen by LaneActivity.seen.collectAsState()
    val timeFormat = remember { AndroidDateFormat.getTimeFormat(context) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel("Lane guidance tests", modifier = Modifier.padding(top = 8.dp))
        LaneTestRows(tests, store)
        Text(
            text = laneTestStatus(tests, asked, seen) { timeFormat.format(Date(it)) },
            style = SettingsStyle.RowSubtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun LaneTestRows(
    tests: LaneTestConfig,
    store: LaneTestStore,
) {
    SettingsGroup {
        SwitchRow(
            title = "Ask iPhone for lanes",
            subtitle = "Applies at the next connection. Turns itself off if the iPhone refuses it.",
            checked = tests.ask,
            icon = Icons.Default.PhoneIphone,
            onCheckedChange = {
                logWarn("[UI_ACTION] Lane tests: ask iPhone -> $it", tag = "UI")
                store.setAsk(it)
            },
        )
        GroupDivider()
        ChoiceSetting(
            title = "Lanes in the turn icon",
            options = LaneIconMode.entries,
            selected = tests.icon,
            icon = Icons.Default.AltRoute,
            onSelect = {
                logWarn("[UI_ACTION] Lane tests: turn icon -> ${it.code}", tag = "UI")
                store.setIcon(it)
            },
        )
        GroupDivider()
        SwitchRow(
            title = "Separate lane strip for GM",
            subtitle = "Also sends Step lanes and a lane image. Harmless if GM ignores them.",
            checked = tests.strip,
            icon = Icons.Default.ViewWeek,
            onCheckedChange = {
                logWarn("[UI_ACTION] Lane tests: lane strip -> $it", tag = "UI")
                store.setStrip(it)
            },
        )
        GroupDivider()
        ChoiceSetting(
            title = "Lane source",
            subtitle = "The test pattern adds 2 to 6 lanes to every turn of any route, so it can be checked parked.",
            options = LaneSource.entries,
            selected = tests.source,
            icon = Icons.Default.Science,
            onSelect = {
                logWarn("[UI_ACTION] Lane tests: source -> ${it.code}", tag = "UI")
                store.setSource(it)
            },
        )
        GroupDivider()
        CalibrationRows(tests, store)
    }
}

@Composable
private fun CalibrationRows(
    tests: LaneTestConfig,
    store: LaneTestStore,
) {
    SwitchRow(
        title = "Turn icon calibration card",
        subtitle = "Shows a test card instead of the turn icon while on.",
        checked = tests.card,
        icon = Icons.Default.GridOn,
        onCheckedChange = {
            logWarn("[UI_ACTION] Lane tests: calibration card -> $it", tag = "UI")
            store.setCard(it)
        },
    )
    GroupDivider()
    SwitchRow(
        title = "Lane strip calibration card",
        subtitle = "Shows boxes from 1:1 to 8:1 in GM's lane slot during a route, ${LaneSlotCard.FRAME_MS / 1000} s each.",
        checked = tests.slotCard,
        icon = Icons.Default.Straighten,
        onCheckedChange = {
            logWarn("[UI_ACTION] Lane tests: lane strip card -> $it", tag = "UI")
            store.setSlotCard(it)
        },
    )
}

/**
 * The line under the lane tests: what is happening now, most specific first. [time] formats a
 * wall-clock time for the head unit's locale.
 */
internal fun laneTestStatus(
    tests: LaneTestConfig,
    askedThisConnection: Boolean,
    seen: LaneActivity.Seen?,
    time: (Long) -> String = { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) },
): String =
    when {
        tests.card -> "The calibration card is on the cluster in place of the turn icon."
        tests.slotCard -> "Lane strip card: the widest box whose label stays full size is the shape of GM's lane slot."
        tests.source == LaneSource.TEST -> "Test pattern: lanes on every turn. [LANES] lines go to the drive log."
        !tests.ask && tests.refusedAtMs > 0 -> "The iPhone refused lanes at ${time(tests.refusedAtMs)}, so asking turned off. Turn it on to try again."
        tests.ask && seen != null -> "Last lanes from the iPhone: ${seen.lanes} lanes, ${seen.recommended} to be in, at ${time(seen.atMs)}."
        tests.ask && askedThisConnection -> "Lanes asked for at this connection. None received yet."
        tests.ask -> "Lanes are asked for at the next connection. Reset connection applies it now."
        else -> "Lanes are not asked for, so the iPhone sends none."
    }
