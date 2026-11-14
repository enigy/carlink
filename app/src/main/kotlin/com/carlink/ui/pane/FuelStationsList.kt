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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlink.fuel.FuelPrice
import com.carlink.fuel.FuelStations
import com.carlink.fuel.Station
import com.carlink.fuel.StationRow
import com.carlink.fuel.StationStars
import com.carlink.fuel.StationText
import com.carlink.ui.settings.VehicleText

/**
 * The Fuel card's stations ([FuelStations]): what the lookup is doing in words, the [rows], and the source's attribution
 * under them. A row tap goes to [StationActions.tap].
 */
@Composable
internal fun StationsSection(
    view: FuelStations.View,
    rows: List<StationRow>,
    look: FuelLook,
    actions: StationActions,
) {
    val secondary = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    val imperial = look.imperial
    when (view.status) {
        FuelStations.Status.OFF ->
            Text("Turn on Nearby stations in Settings › Vehicle to see stations around", style = MaterialTheme.typography.bodyLarge, color = secondary)
        FuelStations.Status.NEEDS_LOCATION -> AllowLocation(actions.allowLocation)
        else -> {
            // The source's attribution (ODbL for OpenStreetMap) heads its stations, in view without scrolling, with the refresh
            // beside it (owner, 12 Nov).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(view.attribution.takeIf { rows.isNotEmpty() }.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = secondary, modifier = Modifier.weight(1f))
                RefreshButton(view, actions.refresh)
            }
            rows.forEach { StationRowView(it, imperial, actions) }
            note(view, rows.isEmpty(), look)?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = secondary) }
            // Why the free stations stand in for Google's (no key, the month's limit): small print.
            view.notice?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = secondary) }
        }
    }
}

/**
 * All or Starred ([StationStars.Tab]), under the tank's gauge (asked 12 Nov), tall enough to tap at a glance. The bar under
 * the selected tab spans the whole tab, not just its word, and the other tab's word is dimmed (owner, 12 Nov).
 */
@Composable
internal fun FuelTabs(
    tab: StationStars.Tab,
    onTab: (StationStars.Tab) -> Unit,
) {
    val ink = LocalContentColor.current
    SecondaryTabRow(
        selectedTabIndex = tab.ordinal,
        containerColor = Color.Transparent,
        contentColor = ink,
        indicator = { TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tab.ordinal, matchContentSize = false), height = 4.dp, color = ink) },
    ) {
        StationStars.Tab.entries.forEach { t ->
            Tab(
                selected = t == tab,
                onClick = { onTab(t) },
                selectedContentColor = ink,
                unselectedContentColor = ink.copy(alpha = SECONDARY_ALPHA),
                modifier = Modifier.heightIn(min = 64.dp),
                text = { Text(t.label, style = MaterialTheme.typography.titleLarge) },
            )
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
        FuelStations.Status.READY -> nothingWithin(view, look).takeIf { empty }
        FuelStations.Status.OFFLINE -> aged("No internet", "No internet", empty, age)
        FuelStations.Status.BUSY -> aged("The station service is busy, trying again soon", "The station service is busy", empty, age)
        FuelStations.Status.FAILED -> aged("Couldn't get the stations", "Couldn't update", empty, age)
        FuelStations.Status.OFF, FuelStations.Status.NEEDS_LOCATION -> null
    }
}

/** An empty list in words: no stations in the radius, or none starred among them, with how to star one. */
private fun nothingWithin(
    view: FuelStations.View,
    look: FuelLook,
): String {
    val radius = VehicleText.radius(look.radiusMi, look.imperial)
    return when {
        !look.starredTab || view.stations.isEmpty() -> "No stations within $radius"
        else -> "No starred stations within $radius. Star one under All to see it here."
    }
}

/** A failure's words: [alone] with no list, else [before] and the list's age. */
private fun aged(
    alone: String,
    before: String,
    empty: Boolean,
    age: String?,
): String = if (empty || age == null) alone else "$before: these are from $age"

/** One station: its star, name and street, distance with its tags, and the price with its age when the source has one. */
@Composable
private fun StationRowView(
    row: StationRow,
    imperial: Boolean,
    actions: StationActions,
) {
    val secondary = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { actions.tap(row) }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .alpha(if (row.outOfReach) OUT_OF_REACH_ALPHA else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Star(row, actions.star)
        Column(modifier = Modifier.weight(1f)) {
            val type = MaterialTheme.typography
            Text(StationText.title(row.station), style = type.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val words = listOfNotNull(row.distanceM?.let { StationText.distance(it, imperial) }, row.tags, "out of reach".takeIf { row.outOfReach })
            if (words.isNotEmpty()) {
                Text(words.joinToString(" · "), style = type.titleMedium, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        row.price?.let { RowPrice(it, row.posted.takeIf { row.discount != null }) }
    }
}

/** A row's CHEAPEST and NEAREST, or null. */
private val StationRow.tags: String? get() = listOfNotNull("CHEAPEST".takeIf { cheapest }, "NEAREST".takeIf { nearest }).joinToString(" · ").ifEmpty { null }

/**
 * A row's price, and its age, amber once it is over a day old. With a discount program ([posted] not null) the price is
 * what the driver pays, and the posted one goes before the age, so it is clear why the station ranks where it does.
 */
@Composable
private fun RowPrice(
    price: FuelPrice,
    posted: FuelPrice?,
) {
    val now = System.currentTimeMillis()
    Column(horizontalAlignment = Alignment.End) {
        Text(StationText.price(price.amount, price.currency), style = MaterialTheme.typography.titleLarge)
        val at = price.atMs
        val postedWords = posted?.let { StationText.posted(it) }
        if (at != null || postedWords != null) {
            val stale = at != null && StationText.stale(at, now)
            Text(
                listOfNotNull(postedWords, at?.let { StationText.age(it, now) }).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = if (stale) MaterialTheme.colorScheme.tertiary else LocalContentColor.current.copy(alpha = SECONDARY_ALPHA),
            )
        }
    }
}

/** A row's star: a button that stars the station or takes its star away. */
@Composable
private fun Star(
    row: StationRow,
    onStar: (StationRow) -> Unit,
) {
    val tint = if (row.starred) MaterialTheme.colorScheme.tertiary else LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    val icon = if (row.starred) Icons.Filled.Star else Icons.Outlined.StarBorder
    IconButton(onClick = { onStar(row) }, modifier = Modifier.size(80.dp)) {
        Icon(icon, contentDescription = if (row.starred) "Unstar ${row.station.name}" else "Star ${row.station.name}", tint = tint, modifier = Modifier.size(45.dp))
    }
}

/**
 * A station's sheet, its price and age, distance and address, and its source's credit ("Google Maps" for
 * Google's data, the Mac's 11 Nov bench), with Directions and Close.
 */
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
            Text(StationText.title(s), style = MaterialTheme.typography.headlineMedium)
            val price =
                row.price?.let { p ->
                    StationText.price(p.amount, p.currency) +
                        (row.discount?.let { d -> row.posted?.let { " with your ${d.label} discount (${StationText.posted(it)})" } } ?: "") +
                        (p.atMs?.let { ", updated ${StationText.age(it, now)}" } ?: "")
                }
            Text(price ?: "No price", style = MaterialTheme.typography.headlineSmall)
            row.distanceM?.let { d ->
                Text(StationText.distance(d, imperial) + " away" + if (row.outOfReach) ", out of reach on this range" else "", style = MaterialTheme.typography.titleLarge)
            }
            s.address?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            s.credit?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)) }
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
