package com.carlink.ui.pane

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.navigation.GuidanceText
import com.carlink.navigation.ManeuverIconRenderer
import com.carlink.navigation.NavigationState
import java.time.LocalTime
import java.util.Locale

/**
 * The route, as the cluster's trip shows it, for the pane while CarPlay has something other than
 * Maps in front: the turn ahead (its arrow, the distance to it large, the road or exit), the turn
 * after it in a line, and the trip's end — arrival time, time and distance to go, destination.
 * The arrow is the static glyph for the CPManeuverType ([ManeuverIconRenderer]); the composed
 * junction bitmaps stay with the cluster, whose store recycles them under a reader's feet.
 */
@Composable
internal fun DirectionsCard(
    state: NavigationState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imperial = remember { GuidanceText.imperial(Locale.getDefault()) }
    val secondary = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        NextTurn(state, GuidanceText.distance(state.remainDistance, imperial))
        Spacer(modifier = Modifier.weight(1f))
        if (state.hasNextStep) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(ManeuverIconRenderer.drawableForManeuver(state.nextManeuverType ?: 0)),
                    contentDescription = null,
                    tint = secondary,
                    modifier = Modifier.size(THEN_ARROW),
                )
                Text(
                    "Then ${state.nextStepCue.orEmpty()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        HorizontalDivider(color = LocalContentColor.current.copy(alpha = RULE_ALPHA))
        TripEnd(state, imperial, twentyFourHour = DateFormat.is24HourFormat(context), secondary = secondary)
    }
}

/** The arrow and, beside it, the distance to the turn over the road or exit it leads onto. */
@Composable
private fun NextTurn(
    state: NavigationState,
    distance: GuidanceText.Distance,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(ManeuverIconRenderer.drawableForManeuver(state.maneuverType)),
            contentDescription = null,
            modifier = Modifier.size(ARROW),
        )
        Column(modifier = Modifier.padding(start = 20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(distance.value, style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, lineHeight = 88.sp))
                Text(distance.unit, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
            }
            Text(state.stepCue.orEmpty(), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Arrival time large, time and distance left beside it, the destination under them. */
@Composable
private fun TripEnd(
    state: NavigationState,
    imperial: Boolean,
    twentyFourHour: Boolean,
    secondary: Color,
) {
    // Re-read on every publish (about once a second), so the arrival time tracks the clock.
    val arrival = remember(state.timeToDestination) { GuidanceText.arrival(state.timeToDestination, LocalTime.now(), twentyFourHour) }
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(arrival, style = MaterialTheme.typography.headlineLarge)
            Text("arrival", style = MaterialTheme.typography.titleMedium, color = secondary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "${GuidanceText.duration(state.timeToDestination)} · ${GuidanceText.distance(state.distanceToDestination, imperial)}",
                style = MaterialTheme.typography.titleMedium,
                color = secondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        val destination = state.destinationName?.takeIf { it.isNotBlank() }
        if (destination != null) {
            Text(
                destination,
                style = MaterialTheme.typography.titleMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

private val ARROW = 150.dp
private val THEN_ARROW = 36.dp
private const val SECONDARY_ALPHA = 0.72f
private const val RULE_ALPHA = 0.25f
