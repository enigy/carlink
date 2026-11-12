package com.carlink.ui.pane

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.carlink.ui.settings.VehicleText
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The idle card: the time, in the head unit's 12- or 24-hour style, and the date, ticking on the
 * second; and along the bottom, once the truck reports them ([VehicleReadings]), its speed and the
 * outside temperature side by side (owner, 10-27: prototype B, "Dashboard strip", in place of a
 * separate Vehicle card). A reading the car does not give is left out, and the strip with both. Under the
 * date, the distance to empty once the car gives its range, in its own distance unit like the Fuel card's
 * ([ToEmpty], owner, 12 Nov: the backgrounds canvas's F2).
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
            VehicleText.rangeFigure(vehicle, vehicle.distanceImperial(Locale.getDefault()))?.let { (range, unit) -> ToEmpty(range, unit) }
        }
        VehicleStrip(speed = vehicle.speed(imperial), speedUnit = if (imperial) "mph" else "km/h", outside = vehicle.outside(imperial))
    }
}

/** "238 mi to empty" under the date, a pump beside it. */
@Composable
private fun ToEmpty(
    range: Int,
    unit: String,
) {
    val words = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 36.sp)
    Row(modifier = Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.LocalGasStation, contentDescription = null, modifier = Modifier.size(30.dp))
        Text("$range $unit", style = words.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"))
        Text("to empty", style = words, color = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA))
    }
}

/**
 * The time and the outside temperature for the pane's header on every other card (owner, 10-27:
 * prototype B, centred): the time minute by minute in the head unit's 12- or 24-hour style, the
 * temperature once the truck reports it ([VehicleReadings]).
 */
@Composable
internal fun HeaderClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timeFormat = remember(context) { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm") }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(MILLIS_PER_MINUTE - System.currentTimeMillis() % MILLIS_PER_MINUTE)
        }
    }
    val vehicle by VehicleReadings.state.collectAsState()
    val outside = vehicle.outside(vehicle.imperial(Locale.getDefault()))
    val words = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    val secondary = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(now.format(timeFormat), style = words)
        if (outside != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Thermostat, contentDescription = "Outside", tint = secondary, modifier = Modifier.size(24.dp))
                Text("$outside°", style = words, color = secondary)
            }
        }
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
private const val MILLIS_PER_MINUTE = 60_000L
private const val SECONDARY_ALPHA = 0.72f
private const val RULE_ALPHA = 0.14f
