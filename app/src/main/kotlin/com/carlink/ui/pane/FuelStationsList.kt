package com.carlink.ui.pane

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlink.fuel.FuelStations
import com.carlink.fuel.Station
import com.carlink.fuel.StationRow
import com.carlink.fuel.StationText
import com.carlink.ui.settings.VehicleText

/**
 * The Fuel card's stations ([FuelStations]): what the lookup is doing in words, the [rows], and the source's attribution
 * under them. Moving ([FuelLook.parked] false), larger rows and no small print. A row tap goes to [onTap].
 */
@Composable
internal fun StationsSection(
    view: FuelStations.View,
    rows: List<StationRow>,
    look: FuelLook,
    onTap: (StationRow) -> Unit,
    onAllowLocation: () -> Unit,
) {
    val secondary = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    val imperial = look.imperial
    val big = !look.parked
    when (view.status) {
        FuelStations.Status.OFF ->
            if (!big) Text("Turn on Nearby stations in Settings › Vehicle to see stations around", style = MaterialTheme.typography.bodyLarge, color = secondary)
        FuelStations.Status.NEEDS_LOCATION -> AllowLocation(onAllowLocation)
        else -> {
            // The source's attribution (ODbL for OpenStreetMap) heads its stations, in view without scrolling, in both layouts.
            if (rows.isNotEmpty()) view.attribution?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = secondary) }
            rows.forEach { StationRowView(it, imperial, big, onTap) }
            note(view, rows.isEmpty(), look)?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = secondary) }
            // Why the free stations stand in for Google's (no key, the month's limit): small print, parked only.
            if (!big) view.notice?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = secondary) }
        }
    }
}

/** The lookup in words, or null while a list is up to date. */
private fun note(
    view: FuelStations.View,
    empty: Boolean,
    look: FuelLook,
): String? {
    val age = view.fetchedAt?.let { "${(SystemClock.elapsedRealtime() - it) / MS_PER_MIN} min ago" }
    return when (view.status) {
        FuelStations.Status.NO_POSITION -> "Waiting for the car's position"
        FuelStations.Status.LOOKING -> "Finding stations nearby".takeIf { empty }
        FuelStations.Status.READY -> "No stations within ${VehicleText.radius(look.radiusMi, look.imperial)}".takeIf { empty }
        FuelStations.Status.OFFLINE -> aged("No internet", "No internet", empty, age)
        FuelStations.Status.BUSY -> aged("The station service is busy, trying again soon", "The station service is busy", empty, age)
        FuelStations.Status.FAILED -> aged("Couldn't get the stations", "Couldn't update", empty, age)
        FuelStations.Status.OFF, FuelStations.Status.NEEDS_LOCATION -> null
    }
}

/** A failure's words: [alone] with no list, else [before] and the list's age. */
private fun aged(
    alone: String,
    before: String,
    empty: Boolean,
    age: String?,
): String = if (empty || age == null) alone else "$before: these are from $age"

/** One station: name and distance with its tags, and the price with its age when the source has one. */
@Composable
private fun StationRowView(
    row: StationRow,
    imperial: Boolean,
    big: Boolean,
    onTap: (StationRow) -> Unit,
) {
    val secondary = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    val now = System.currentTimeMillis()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (big) 88.dp else 72.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { onTap(row) }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .alpha(if (row.outOfReach) OUT_OF_REACH_ALPHA else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Moving: both lines large, no small print (the Mac's 11 Nov bench).
            val type = MaterialTheme.typography
            Text(row.station.name, style = if (big) type.headlineMedium else type.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val words = listOfNotNull(row.distanceM?.let { StationText.distance(it, imperial) }, tags(row), "out of reach".takeIf { row.outOfReach })
            if (words.isNotEmpty()) Text(words.joinToString(" · "), style = if (big) type.titleLarge else type.titleMedium, color = secondary)
        }
        row.station.price?.let { price ->
            Column(horizontalAlignment = Alignment.End) {
                Text(StationText.price(price, row.station.currency), style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge)
                val at = row.station.priceAtMs
                if (!big && at != null) {
                    val stale = StationText.stale(at, now)
                    Text(
                        StationText.age(at, now),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (stale) MaterialTheme.colorScheme.tertiary else secondary,
                    )
                }
            }
        }
    }
}

private fun tags(row: StationRow): String? = listOfNotNull("CHEAPEST".takeIf { row.cheapest }, "NEAREST".takeIf { row.nearest }).joinToString(" · ").ifEmpty { null }

/** Parked: a station's sheet, its price and age, distance and address, with Directions and Close. */
@Composable
internal fun StationSheet(
    row: StationRow,
    imperial: Boolean,
    onDirections: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s: Station = row.station
    val now = System.currentTimeMillis()
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.name, style = MaterialTheme.typography.headlineMedium)
            val price = s.price?.let { p -> StationText.price(p, s.currency) + (s.priceAtMs?.let { ", updated ${StationText.age(it, now)}" } ?: "") }
            Text(price ?: "No price", style = MaterialTheme.typography.headlineSmall)
            row.distanceM?.let { d ->
                Text(StationText.distance(d, imperial) + " away" + if (row.outOfReach) ", out of reach on this range" else "", style = MaterialTheme.typography.titleLarge)
            }
            s.address?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Spacer(modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalButton(onClick = onDirections, modifier = Modifier.heightIn(min = 72.dp)) { Text("Directions", style = MaterialTheme.typography.titleLarge) }
                OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 72.dp)) { Text("Close", style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}

/** Without the location permission: the stations' request, with an Allow at the head unit's touch height. */
@Composable
private fun AllowLocation(onAllow: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Allow Carlink to use the car's location to find stations", style = MaterialTheme.typography.titleLarge)
        FilledTonalButton(onClick = onAllow, modifier = Modifier.heightIn(min = 72.dp)) {
            Text("Allow", style = MaterialTheme.typography.titleLarge)
        }
    }
}

private const val SECONDARY_ALPHA = 0.72f
private const val OUT_OF_REACH_ALPHA = 0.45f
private const val MS_PER_MIN = 60_000L
