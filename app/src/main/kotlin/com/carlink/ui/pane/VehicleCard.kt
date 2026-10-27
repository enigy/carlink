package com.carlink.ui.pane

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlink.car.VehicleData
import java.util.Locale

/**
 * The truck, in the clock card's manner: its speed large, as the speedometer shows it, with the unit
 * under it, and the outside temperature below. A reading the car does not give is left out. Miles or
 * kilometres follow the truck's own setting ([com.carlink.car.VehicleState.imperial]).
 */
@Composable
internal fun VehicleCard(modifier: Modifier = Modifier) {
    val vehicle by VehicleData.state.collectAsState()
    val imperial = vehicle.imperial(Locale.getDefault())
    val secondary = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    val speed = vehicle.speed(imperial)
    val outside = vehicle.outside(imperial)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (speed != null) {
            Text("$speed", style = MaterialTheme.typography.displayLarge.copy(fontSize = 112.sp, lineHeight = 120.sp))
            Text(if (imperial) "mph" else "km/h", style = MaterialTheme.typography.titleLarge, color = secondary)
        }
        if (speed != null && outside != null) Spacer(modifier = Modifier.height(48.dp))
        if (outside != null) {
            Text("$outside°", style = MaterialTheme.typography.displayMedium)
            Text("Outside", style = MaterialTheme.typography.titleLarge, color = secondary)
        }
    }
}

private const val SECONDARY_ALPHA = 0.72f
