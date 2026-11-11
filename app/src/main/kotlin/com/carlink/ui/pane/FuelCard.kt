package com.carlink.ui.pane

import android.Manifest
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.carlink.fuel.FuelStations
import com.carlink.fuel.LowFuel
import com.carlink.fuel.Station
import com.carlink.fuel.StationList
import com.carlink.fuel.StationRow
import com.carlink.ui.settings.VehicleStore
import com.carlink.ui.settings.VehicleText
import java.util.Locale

/**
 * The Fuel card: the truck's range in the car's own distance unit, what it runs on ([CarFuel]: the car's fuel types,
 * the driver's engine override and grade), and the low-fuel warning as an amber banner ([LowFuel]: the car's warning,
 * or the range at the driver's threshold); then, with Settings › Vehicle › Nearby stations on, the stations around
 * ([FuelStations]). Parked, it adds the tank's bar (only when the level and the tank's size are both read), small print
 * and up to four stations, a tap opening a station's sheet; moving, the range, the fuel and at most three big rows in
 * large type, no small print, no scrolling, a tap starting directions at once ([onDirections]). Unknown counts as
 * moving ([DrivingState.parked]).
 *
 * Range, the level and the warning need CAR_ENERGY, and the stations the location permission. The card asks for
 * CAR_ENERGY by itself once per run, when it is opened parked, never at the app's start (the buried-dialog bug of 11
 * Nov); its Allow buttons work even while an earlier dialog still waits, and after "don't ask again" they open the app's
 * settings ([PermissionPrompt]). [active]: the card is the one up, not just passing in a swipe.
 */
@Composable
internal fun FuelCard(
    active: Boolean,
    onDirections: (Station) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember(context) { VehicleStore.get(context) }
    val vehicle by VehicleReadings.state.collectAsState()
    val parked by DrivingState.parked.collectAsState()
    val engine by store.engineChoice.collectAsState()
    val grade by store.fuelGrade.collectAsState()
    val lowAt by store.lowFuelAt.collectAsState()
    val nearby by store.nearbyStations.collectAsState()
    // Read and watch range, the level and the warning as soon as they are allowed.
    val energy = rememberCardPermission(arrayOf(VehicleData.ENERGY_PERMISSION)) { VehicleData.start(context.applicationContext) }
    val location = rememberCardPermission(LOCATION_PERMISSIONS)
    LaunchedEffect(active, parked) {
        if (!active || !parked || energy.allowed) return@LaunchedEffect
        if (FuelCardPrompt.asked) return@LaunchedEffect
        FuelCardPrompt.asked = true
        energy.ask(fromButton = false)
    }
    val imperial = vehicle.distanceImperial(Locale.getDefault())
    val low = LowFuel.isLow(vehicle, lowAt, imperial)
    FollowStations(active, nearby, location.allowed, low)
    val fuel = CarFuel.of(vehicle.fuelTypes, engine, grade)
    var picked by remember { mutableStateOf<StationRow?>(null) }
    LaunchedEffect(parked) { if (!parked) picked = null } // the sheet is a parked view
    Box(modifier = modifier) {
        val scroll = if (parked) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(modifier = Modifier.fillMaxSize().then(scroll), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val look = FuelLook(parked, low, imperial)
            CarData(vehicle, fuel, look, energy)
            if (fuel.hasStations) {
                Stations(vehicle, look, onAllowLocation = { location.ask(fromButton = true) }) { row ->
                    if (parked) picked = row else onDirections(row.station)
                }
            }
        }
        picked?.let { row ->
            StationSheet(
                row = row,
                imperial = imperial,
                onDirections = {
                    picked = null
                    onDirections(row.station)
                },
                onClose = { picked = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The car's part: the low-fuel banner, the range (or the energy request), the fuel, and parked the tank and small print. */
@Composable
private fun CarData(
    vehicle: VehicleState,
    fuel: CarFuel,
    look: FuelLook,
    energy: CardPermission,
) {
    val secondary = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
    val imperial = look.imperial
    if (look.low) LowFuelBanner()
    if (energy.allowed) Range(vehicle, imperial) else AllowFuel { energy.ask(fromButton = true) }
    Text(fuel.label, style = MaterialTheme.typography.headlineMedium, color = if (fuel.kind == CarFuel.Kind.UNKNOWN) secondary else LocalContentColor.current)
    if (look.parked) {
        vehicle.fuelFraction()?.let { TankBar(it) }
        VehicleText.tank(vehicle, imperial)?.let { Text("Tank: $it", style = MaterialTheme.typography.titleMedium, color = secondary) }
        Text("Range and fuel from the truck", style = MaterialTheme.typography.bodyLarge, color = secondary)
    }
}

/** The stations around ([FuelStations]), as many rows as the layout takes. */
@Composable
private fun Stations(
    vehicle: VehicleState,
    look: FuelLook,
    onAllowLocation: () -> Unit,
    onTap: (StationRow) -> Unit,
) {
    val view by FuelStations.view.collectAsState()
    val here by FuelStations.here.collectAsState()
    val rows = StationList.rows(view.stations, here, vehicle.rangeM, cheapestFirst = look.parked && !look.low, max = if (look.parked) PARKED_ROWS else MOVING_ROWS)
    StationsSection(view, rows, look, onTap = onTap, onAllowLocation = onAllowLocation)
}

/** Tells [FuelStations] when the card is up, a setting it hangs on changed, or low fuel came on. */
@Composable
private fun FollowStations(
    active: Boolean,
    nearby: Boolean,
    locationAllowed: Boolean,
    low: Boolean,
) {
    val context = LocalContext.current
    LaunchedEffect(active, nearby, locationAllowed) { FuelStations.onCard(context, active) }
    LaunchedEffect(low) { if (low) FuelStations.onLowFuel(context) }
    DisposableEffect(Unit) { onDispose { FuelStations.onCard(context, visible = false) } }
}

/** How the card lays out now: [parked] (else moving: the big layout), the fuel [low], distances in miles ([imperial]). */
internal data class FuelLook(
    val parked: Boolean,
    val low: Boolean,
    val imperial: Boolean,
)

/** A runtime permission the card asks for: whether it is granted now, and the ask. */
private class CardPermission(
    val allowed: Boolean,
    private val asker: (fromButton: Boolean) -> Unit,
) {
    /** [fromButton]: the driver tapped Allow, so a refusal without a dialog opens the app's settings. */
    fun ask(fromButton: Boolean) = asker(fromButton)
}

@Composable
private fun rememberCardPermission(
    permissions: Array<String>,
    onGranted: () -> Unit = {},
): CardPermission {
    val context = LocalContext.current
    val granted by rememberUpdatedState(onGranted)
    var allowed by remember { mutableStateOf(isGranted(context, permissions[0])) }
    var fromButton by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val ok = isGranted(context, permissions[0])
            if (PermissionPrompt.answered(ok) && fromButton) PermissionPrompt.openAppSettings(context)
            allowed = ok
            if (ok) granted()
        }
    // Back from the system's app settings, or a grant made in Settings › Vehicle.
    LifecycleResumeEffect(Unit) {
        allowed = isGranted(context, permissions[0])
        onPauseOrDispose { }
    }
    return CardPermission(allowed) { button ->
        fromButton = button
        PermissionPrompt.askOrOpenSettings(context, launcher, permissions)
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

/** The amber "Low fuel" banner, while the fuel is low. */
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

private fun isGranted(
    context: Context,
    permission: String,
): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** The stations' permission: the one Car GPS uses, asked with its coarse twin, as Android 12 requires. */
private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private const val SECONDARY_ALPHA = 0.72f
private const val TRACK_ALPHA = 0.22f
private const val PARKED_ROWS = 4
private const val MOVING_ROWS = 3
