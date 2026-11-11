package com.carlink.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.carlink.PermissionPrompt
import com.carlink.car.CarGpsFeed
import com.carlink.car.VehicleData
import com.carlink.car.VehicleReadings
import com.carlink.car.VehicleState
import com.carlink.car.VehicleStatusValues
import com.carlink.logging.logWarn
import java.util.Locale

/** The location permission Car GPS for the iPhone needs. */
private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private val VEHICLE_PANE_MAX_WIDTH = 1100.dp

/**
 * Vehicle: what the truck gives the iPhone ([VehicleStore]). Car GPS for the iPhone (on by default), then the data
 * points the iPhone may have, each with what Carlink reads now. Changes apply the next time the phone connects.
 */
@Composable
internal fun VehiclePane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { VehicleStore.get(context) }
    val gps by store.carGps.collectAsState()
    val shares by store.shares.collectAsState()
    val engine by store.engineChoice.collectAsState()
    val readings by VehicleReadings.state.collectAsState()
    var locationAllowed by remember { mutableStateOf(CarGpsFeed.permitted(context)) }
    var energyAllowed by remember { mutableStateOf(granted(context, VehicleData.ENERGY_PERMISSION)) }
    val askLocation =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            PermissionPrompt.answered()
            locationAllowed = CarGpsFeed.permitted(context)
        }
    val askEnergy =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            PermissionPrompt.answered()
            energyAllowed = ok
            if (ok) VehicleData.start(context.applicationContext) // read and watch range and the fuel warning now
        }
    // Back from the system's app settings ([ask]): take up what was granted there.
    LifecycleResumeEffect(Unit) {
        locationAllowed = CarGpsFeed.permitted(context)
        val energyNow = granted(context, VehicleData.ENERGY_PERMISSION)
        if (energyNow && !energyAllowed) VehicleData.start(context.applicationContext)
        energyAllowed = energyNow
        onPauseOrDispose { }
    }

    Column(modifier = modifier.widthIn(max = VEHICLE_PANE_MAX_WIDTH), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PaneHeader(title = "Vehicle", subtitle = "What the truck gives the iPhone. Changes apply the next time the iPhone connects.")
        GpsGroup(store, gps, locationAllowed, onAsk = { ask(context, askLocation, LOCATION_PERMISSIONS) })
        EngineGroup(store, engine, readings)
        SectionLabel("Shared with the iPhone")
        SharedGroup(
            store = store,
            shares = shares,
            readings = readings,
            energyAllowed = energyAllowed,
            // Range and the low-fuel warning need CAR_ENERGY: asked for when either is turned on.
            onEnergyNeeded = { if (!energyAllowed) ask(context, askEnergy, VehicleData.ENERGY_PERMISSION) },
        )
    }
}

/**
 * Ask for a permission or, while an earlier dialog still waits unanswered (Android would refuse this request without
 * asking), open Carlink's page in the system's app settings instead.
 */
private fun <I> ask(
    context: Context,
    launcher: ActivityResultLauncher<I>,
    input: I,
) {
    if (PermissionPrompt.isOpen && PermissionPrompt.openAppSettings(context)) return
    PermissionPrompt.ask(launcher, input)
}

/** Car GPS for the iPhone, and the location permission it needs while it's on. */
@Composable
private fun GpsGroup(
    store: VehicleStore,
    gps: Boolean,
    locationAllowed: Boolean,
    onAsk: () -> Unit,
) {
    SettingsGroup {
        SwitchRow(
            title = "Car GPS for the iPhone",
            subtitle = VehicleText.gps(gps, locationAllowed),
            checked = gps,
            icon = Icons.Default.GpsFixed,
            onCheckedChange = { on ->
                logWarn("[UI_ACTION] Car GPS for the iPhone -> $on", tag = "UI")
                store.setCarGps(on)
                if (on && !locationAllowed) onAsk()
            },
        )
        if (gps && !locationAllowed) {
            GroupDivider()
            SettingRow(title = "Location permission", subtitle = "Lets Carlink read the truck's GPS for the iPhone") {
                RowAction("Allow", onClick = onAsk)
            }
        }
    }
}

/** The engine type the iPhone always gets: the car's own, or the driver's pick for a car that reports none or the wrong one. */
@Composable
private fun EngineGroup(
    store: VehicleStore,
    engine: EngineChoice,
    readings: VehicleState,
) {
    SettingsGroup {
        ChoiceSetting(
            title = "Engine type",
            subtitle = VehicleText.engine(engine, readings),
            options = EngineChoice.entries,
            selected = engine,
            onSelect = {
                logWarn("[UI_ACTION] Engine type -> ${it.code}", tag = "UI")
                store.setEngineChoice(it)
            },
        )
    }
}

/** The data points the iPhone may have, each with what Carlink reads now. */
@Composable
private fun SharedGroup(
    store: VehicleStore,
    shares: VehicleStatusValues.Shares,
    readings: VehicleState,
    energyAllowed: Boolean,
    onEnergyNeeded: () -> Unit,
) {
    val imperial = readings.imperial(Locale.getDefault())
    val set: (VehicleStatusValues.Shares, Boolean) -> Unit = { next, needsEnergy ->
        logWarn("[UI_ACTION] Vehicle shares -> $next", tag = "UI")
        store.setShares(next)
        if (needsEnergy) onEnergyNeeded()
    }
    SettingsGroup {
        SwitchRow(
            title = "Range",
            subtitle = VehicleText.range(readings, imperial, energyAllowed),
            checked = shares.range,
            onCheckedChange = { set(shares.copy(range = it), it) },
        )
        GroupDivider()
        SwitchRow(
            title = "Low fuel warning",
            subtitle = VehicleText.lowFuel(readings, energyAllowed),
            checked = shares.lowFuel,
            onCheckedChange = { set(shares.copy(lowFuel = it), it) },
        )
        GroupDivider()
        SwitchRow(
            title = "Outside temperature",
            subtitle = VehicleText.outside(readings, imperial),
            checked = shares.outsideTemperature,
            onCheckedChange = { set(shares.copy(outsideTemperature = it), false) },
        )
    }
}

private fun granted(
    context: Context,
    permission: String,
): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
