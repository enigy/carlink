package com.carlink.ui.pane

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.car.VehicleReadings
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The idle card: the time, in the head unit's 12- or 24-hour style, and the date, ticking on the
 * second; and along the bottom, once the truck reports them ([VehicleReadings]), its speed and the
 * outside temperature side by side (owner, 10-27: prototype B, "Dashboard strip", in place of a
 * separate Vehicle card). A reading the car does not give is left out, and the strip with both.
 */
@Composable
internal fun ClockCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timeFormat = remember(context) { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm") }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(MILLIS_PER_SECOND - System.currentTimeMillis() % MILLIS_PER_SECOND)
        }
    }
    val vehicle by VehicleReadings.state.collectAsState()
    val imperial = vehicle.imperial(Locale.getDefault())
    val secondary = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    Column(modifier = modifier) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(now.format(timeFormat), style = MaterialTheme.typography.displayLarge.copy(fontSize = 112.sp, lineHeight = 120.sp))
            Text(now.format(DATE_FORMAT), style = MaterialTheme.typography.titleLarge, color = secondary)
        }
        VehicleStrip(speed = vehicle.speed(imperial), speedUnit = if (imperial) "mph" else "km/h", outside = vehicle.outside(imperial))
    }
}

/** Speed and outside temperature as two large readings under a hairline, a hairline between them. Nothing when neither is known. */
@Composable
private fun VehicleStrip(
    speed: Int?,
    speedUnit: String,
    outside: Int?,
) {
    if (speed == null && outside == null) return
    val rule = LocalContentColor.current.copy(alpha = RULE_ALPHA)
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = rule)
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 28.dp, bottom = 24.dp)) {
            if (speed != null) Reading(value = "$speed", unit = speedUnit, label = "Speed", modifier = Modifier.weight(1f))
            if (speed != null && outside != null) VerticalDivider(thickness = 1.dp, color = rule)
            if (outside != null) Reading(value = "$outside°", unit = null, label = "Outside", modifier = Modifier.weight(1f))
        }
    }
}

/** One reading: the figure large with its unit beside it on the baseline, the label under it. */
@Composable
private fun Reading(
    value: String,
    unit: String?,
    label: String,
    modifier: Modifier = Modifier,
) {
    val secondary = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    val figure = MaterialTheme.typography.displayMedium.copy(fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            Text(value, style = figure, modifier = Modifier.alignByBaseline())
            if (unit != null) {
                Text(unit, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), color = secondary, modifier = Modifier.alignByBaseline().padding(start = 8.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = secondary)
    }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
internal const val MILLIS_PER_SECOND = 1_000L
private const val SECONDARY_ALPHA = 0.72f
private const val RULE_ALPHA = 0.14f
