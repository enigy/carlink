package com.carlink.ui.pane

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.carlink.PermissionPrompt
import com.carlink.car.DrivingState
import com.carlink.car.VehicleData
import com.carlink.car.VehicleReadings
import com.carlink.car.VehicleState
import com.carlink.fuel.CarFuel
import com.carlink.ui.settings.VehicleStore
import com.carlink.ui.settings.VehicleText
import java.util.Locale

/**
 * The Fuel card, from the car alone (no network): the truck's range in the car's own distance unit, what it runs on
 * ([CarFuel]: the car's fuel types, the driver's engine override and grade), and the low-fuel warning as an amber
 * banner. Parked, it adds the tank's bar (only when the level and the tank's size are both read) and its small print;
 * moving, the range and the fuel in large type only, and unknown counts as moving ([DrivingState.parked]).
 *
 * Range, the level and the warning need CAR_ENERGY. The card asks for it by itself once per run, when it is opened
 * parked, never at the app's start (the buried-dialog bug of 11 Nov); its Allow works even while an earlier dialog
 * still waits ([PermissionPrompt.askOrOpenSettings]). [active]: the card is the one up, not just passing in a swipe.
 */
@Composable
internal fun FuelCard(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) { VehicleStore.get(context) }
    val vehicle by VehicleReadings.state.collectAsState()
    val parked by DrivingState.parked.collectAsState()
    val engine by store.engineChoice.collectAsState()
    val grade by store.fuelGrade.collectAsState()
    var energyAllowed by remember { mutableStateOf(energyGranted(context)) }
    val askEnergy =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            PermissionPrompt.answered()
            energyAllowed = ok
            if (ok) VehicleData.start(context.applicationContext) // read and watch range, the level and the warning now
        }
    // Back from the system's app settings, or a grant made in Settings › Vehicle.
    LifecycleResumeEffect(Unit) {
        energyAllowed = energyGranted(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(active, parked) {
        if (!active || !parked || energyAllowed) return@LaunchedEffect
        if (FuelCardPrompt.asked) return@LaunchedEffect
        FuelCardPrompt.asked = true
        PermissionPrompt.askOrOpenSettings(context, askEnergy, VehicleData.ENERGY_PERMISSION)
    }
    val fuel = CarFuel.of(vehicle.fuelTypes, engine, grade)
    val imperial = vehicle.distanceImperial(Locale.getDefault())
    val secondary = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (vehicle.fuelLow == true) LowFuelBanner()
        if (energyAllowed) {
            Range(vehicle, imperial)
        } else {
            AllowFuel { PermissionPrompt.askOrOpenSettings(context, askEnergy, VehicleData.ENERGY_PERMISSION) }
        }
        Text(fuel.label, style = MaterialTheme.typography.headlineMedium, color = if (fuel.kind == CarFuel.Kind.UNKNOWN) secondary else LocalContentColor.current)
        if (parked) {
            vehicle.fuelFraction()?.let { TankBar(it) }
            VehicleText.tank(vehicle, imperial)?.let { Text("Tank: $it", style = MaterialTheme.typography.titleMedium, color = secondary) }
            Text("Range and fuel from the truck", style = MaterialTheme.typography.bodyLarge, color = secondary)
        }
    }
}

/** The range large, its unit beside it; or that the car hasn't given it yet. */
@Composable
private fun Range(
    vehicle: VehicleState,
    imperial: Boolean,
) {
    val figure = VehicleText.rangeFigure(vehicle, imperial)
    if (figure == null) {
        Text("Range not read from the car yet", style = MaterialTheme.typography.headlineSmall, color = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA))
        return
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(figure.first.toString(), style = MaterialTheme.typography.displayLarge.copy(fontSize = 104.sp, lineHeight = 112.sp))
        Text("${figure.second} range", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, bottom = 18.dp))
    }
}

/** The amber "Low fuel" banner, while the car's low-fuel warning is on. */
@Composable
private fun LowFuelBanner() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.tertiary)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = colors.onTertiary, modifier = Modifier.size(40.dp))
        Text("Low fuel", style = MaterialTheme.typography.headlineSmall, color = colors.onTertiary, modifier = Modifier.padding(start = 16.dp))
    }
}

/** Without CAR_ENERGY: the card's request, with an Allow at the head unit's touch height. */
@Composable
private fun AllowFuel(onAllow: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Allow Carlink to read the fuel", style = MaterialTheme.typography.headlineSmall)
        FilledTonalButton(onClick = onAllow, modifier = Modifier.heightIn(min = 72.dp)) {
            Text("Allow", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** The tank's level as a bar, full width. */
@Composable
private fun TankBar(fraction: Float) {
    val content = LocalContentColor.current
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(content.copy(alpha = TRACK_ALPHA)),
    ) {
        Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(content))
    }
}

/** The card asks for CAR_ENERGY by itself at most once per run of the app. */
private object FuelCardPrompt {
    @Volatile var asked = false
}

private fun energyGranted(context: Context): Boolean = context.checkSelfPermission(VehicleData.ENERGY_PERMISSION) == PackageManager.PERMISSION_GRANTED

private const val SECONDARY_ALPHA = 0.72f
private const val TRACK_ALPHA = 0.22f
