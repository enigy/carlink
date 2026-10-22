package com.carlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneIphone
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
import java.text.DateFormat
import java.util.Date
import android.text.format.DateFormat as AndroidDateFormat

/**
 * Settings › Cluster › Lane guidance: the lanes for the next turn on GM's cluster and HUD, in the
 * turn icon, in GM's own lane area, or both, with the lanes to be in in a color of the driver's
 * choosing. All off by default. Only "Ask iPhone for lanes" reaches the adapter; the rest change what
 * Carlink3 draws ([com.carlink.navigation.ClusterStep]).
 */
@Composable
internal fun LaneGuidanceGroup(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { LaneGuidanceStore.get(context) }
    val cfg by store.flow.collectAsState()
    val asked by LaneActivity.askedThisConnection.collectAsState()
    val seen by LaneActivity.seen.collectAsState()
    val timeFormat = remember { AndroidDateFormat.getTimeFormat(context) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel("Lane guidance", modifier = Modifier.padding(top = 8.dp))
        LaneGuidanceRows(cfg, store)
        Text(
            text = laneGuidanceStatus(cfg, asked, seen) { timeFormat.format(Date(it)) },
            style = SettingsStyle.RowSubtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun LaneGuidanceRows(
    cfg: LaneGuidanceConfig,
    store: LaneGuidanceStore,
) {
    SettingsGroup {
        SwitchRow(
            title = "Ask iPhone for lanes",
            subtitle = "Applies at the next connection. Turns itself off if the iPhone refuses it.",
            checked = cfg.ask,
            icon = Icons.Default.PhoneIphone,
            onCheckedChange = {
                logWarn("[UI_ACTION] Lane guidance: ask iPhone -> $it", tag = "UI")
                store.setAsk(it)
            },
        )
        GroupDivider()
        ChoiceSetting(
            title = "Lanes in the turn icon",
            options = LaneIconMode.entries,
            selected = cfg.icon,
            icon = Icons.Default.AltRoute,
            onSelect = {
                logWarn("[UI_ACTION] Lane guidance: turn icon -> ${it.code}", tag = "UI")
                store.setIcon(it)
            },
        )
        GroupDivider()
        SwitchRow(
            title = "Separate lane strip for GM",
            subtitle = "The lanes across GM's lane area, under the turn.",
            checked = cfg.strip,
            icon = Icons.Default.ViewWeek,
            onCheckedChange = {
                logWarn("[UI_ACTION] Lane guidance: lane strip -> $it", tag = "UI")
                store.setStrip(it)
            },
        )
        GroupDivider()
        LaneColorRow(cfg, store)
    }
}

/** The color of the lanes to be in, in the turn icon and the lane strip: white or the turn alert's palette. */
@Composable
private fun LaneColorRow(
    cfg: LaneGuidanceConfig,
    store: LaneGuidanceStore,
) {
    val choices = listOf<InlayColor?>(null) + InlayColor.entries
    SettingRow(title = "Lanes to be in", subtitle = cfg.laneColor?.label ?: "White", icon = Icons.Default.Palette) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            choices.forEach { c ->
                Swatch(
                    argb = c?.argb ?: WHITE_ARGB,
                    label = c?.label ?: "White",
                    selected = c == cfg.laneColor,
                    onClick = {
                        logWarn("[UI_ACTION] Lane guidance: lane color -> ${c?.code ?: "white"}", tag = "UI")
                        store.setLaneColor(c)
                    },
                )
            }
        }
    }
}

private const val WHITE_ARGB = 0xFFFFFFFF.toInt()

/**
 * The line under Lane guidance: what is happening now, most specific first. [time] formats a
 * wall-clock time for the head unit's locale.
 */
internal fun laneGuidanceStatus(
    cfg: LaneGuidanceConfig,
    askedThisConnection: Boolean,
    seen: LaneActivity.Seen?,
    time: (Long) -> String = { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) },
): String =
    when {
        !cfg.ask && cfg.refusedAtMs > 0 -> "The iPhone refused lanes at ${time(cfg.refusedAtMs)}, so asking turned off. Turn it on to try again."
        cfg.ask && seen != null -> "Last lanes from the iPhone: ${seen.lanes} lanes, ${seen.recommended} to be in, at ${time(seen.atMs)}."
        cfg.ask && askedThisConnection -> "Lanes asked for at this connection. None received yet."
        cfg.ask -> "Lanes are asked for at the next connection. Reset connection applies it now."
        else -> "Lanes are not asked for, so the iPhone sends none."
    }
