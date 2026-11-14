package com.carlink.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.carlink.fuel.CarFuel
import com.carlink.fuel.LowFuel
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
            locationAllowed = CarGpsFeed.permitted(context)
            // Refused without a dialog ("don't ask again"): the app's settings are the one place left to allow it.
            if (PermissionPrompt.answered(locationAllowed)) PermissionPrompt.openAppSettings(context)
        }
    val askEnergy =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (PermissionPrompt.answered(ok)) PermissionPrompt.openAppSettings(context)
            energyAllowed = ok
            if (ok) VehicleData.start(context.applicationContext) // read and watch range and the fuel warning now
        }
    // Back from the system's app settings ([PermissionPrompt.askOrOpenSettings]): take up what was granted there.
    LifecycleResumeEffect(Unit) {
        locationAllowed = CarGpsFeed.permitted(context)
        val energyNow = granted(context, VehicleData.ENERGY_PERMISSION)
        if (energyNow && !energyAllowed) VehicleData.start(context.applicationContext)
        energyAllowed = energyNow
        onPauseOrDispose { }
    }

    val askEnergyIfNeeded = { if (!energyAllowed) PermissionPrompt.askOrOpenSettings(context, askEnergy, VehicleData.ENERGY_PERMISSION) }

    Column(modifier = modifier.widthIn(max = VEHICLE_PANE_MAX_WIDTH), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PaneHeader(title = "Vehicle", subtitle = "What the truck gives the iPhone. Changes apply the next time the iPhone connects.")
        GpsGroup(store, gps, locationAllowed, onAsk = { PermissionPrompt.askOrOpenSettings(context, askLocation, LOCATION_PERMISSIONS) })
        EngineGroup(store, engine, readings)
        SectionLabel("Shared with the iPhone")
        SharedGroup(
            store = store,
            shares = shares,
            readings = readings,
            energyAllowed = energyAllowed,
            // Range and the low-fuel warning need CAR_ENERGY: asked for when either is turned on.
            onEnergyNeeded = askEnergyIfNeeded,
        )
        SectionLabel("Fuel card")
        FuelCardGroup(store, readings, engine, onEnergyNeeded = askEnergyIfNeeded)
        NearbyGroup(store, locationAllowed, readings.distanceImperial(Locale.getDefault())) {
            if (!locationAllowed) PermissionPrompt.askOrOpenSettings(context, askLocation, LOCATION_PERMISSIONS)
        }
    }
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
internal fun EngineGroup(
    store: VehicleStore,
    engine: EngineChoice,
    readings: VehicleState,
) {
    SettingsGroup {
        ChoiceSetting(
            title = "Engine type",
            subtitle = VehicleText.engine(engine, readings),
            options = EngineChoice.choices,
            // Nothing picked: the car's own, or Gasoline, the adapter's default, before the car has said.
            selected =
                if (engine == EngineChoice.CAR) {
                    EngineChoice.reported(VehicleStatusValues.engines(readings.fuelTypes)) ?: EngineChoice.GASOLINE
                } else {
                    engine
                },
            onSelect = {
                logWarn("[UI_ACTION] Engine type -> ${it.code}", tag = "UI")
                store.setEngineChoice(it)
            },
        )
    }
}

/**
 * The side pane's Fuel card: whether the deck has it, whether low fuel brings it up, and a gasoline car's grade. Turning
 * either on asks for CAR_ENERGY, which the card's range and warning need.
 */
@Composable
private fun FuelCardGroup(
    store: VehicleStore,
    readings: VehicleState,
    engine: EngineChoice,
    onEnergyNeeded: () -> Unit,
) {
    val card by store.fuelCard.collectAsState()
    val onLow by store.fuelOnLow.collectAsState()
    val grade by store.fuelGrade.collectAsState()
    val lowAt by store.lowFuelAt.collectAsState()
    val fuel = CarFuel.of(readings.fuelTypes, engine, grade)
    val unit = if (readings.distanceImperial(Locale.getDefault())) "mi" else "km"
    SettingsGroup {
        SwitchRow(
            title = "Show the Fuel card",
            subtitle = "Range and fuel in the side pane, from the truck",
            checked = card,
            onCheckedChange = { on ->
                logWarn("[UI_ACTION] Fuel card -> $on", tag = "UI")
                store.setFuelCard(on)
                if (on) onEnergyNeeded()
            },
        )
        GroupDivider()
        SwitchRow(
            title = "Show on low fuel",
            subtitle = "The pane turns to the Fuel card when the low-fuel warning comes on",
            checked = onLow,
            onCheckedChange = { on ->
                logWarn("[UI_ACTION] Fuel card on low fuel -> $on", tag = "UI")
                store.setFuelOnLow(on)
                if (on) onEnergyNeeded()
            },
        )
        GroupDivider()
        LowFuelAtRow(store, lowAt, unit)
        if (fuel.kind == CarFuel.Kind.GASOLINE) {
            GroupDivider()
            ChoiceSetting(
                title = "Fuel grade",
                subtitle = VehicleText.gradeNote(engine),
                options = CarFuel.grades(readings.fuelTypes),
                selected = fuel.grade ?: FuelGrade.REGULAR,
                onSelect = {
                    logWarn("[UI_ACTION] Fuel grade -> ${it.code}", tag = "UI")
                    store.setFuelGrade(it)
                },
            )
        }
    }
}

/**
 * Nearby stations (off by default): the Fuel card looks up stations around, which sends the car's position to the source
 * (OpenStreetMap, or Google with the driver's key). Turning it on asks for the location permission Car GPS uses. Its
 * Price source and Search radius follow ([PriceSourceGroup]).
 */
@Composable
private fun NearbyGroup(
    store: VehicleStore,
    locationAllowed: Boolean,
    imperial: Boolean,
    onLocationNeeded: () -> Unit,
) {
    val nearby by store.nearbyStations.collectAsState()
    SettingsGroup {
        SwitchRow(
            title = "Nearby stations",
            subtitle =
                if (nearby && !locationAllowed) {
                    "On, but Carlink needs the location permission to find stations"
                } else {
                    "Sends the car's position to OpenStreetMap, or Google with your key, to find fuel stations around. Off by default."
                },
            checked = nearby,
            onCheckedChange = { on ->
                logWarn("[UI_ACTION] Nearby stations -> $on", tag = "UI")
                store.setNearbyStations(on)
                if (on) onLocationNeeded()
            },
        )
    }
    if (nearby) PriceSourceGroup(store, imperial)
}

/** Low fuel warning at: the range, in the car's [unit], at which the Fuel card warns ([LowFuel], default 25). */
@Composable
private fun LowFuelAtRow(
    store: VehicleStore,
    lowAt: Int,
    unit: String,
) {
    ChoiceSetting(
        title = "Low fuel warning at",
        subtitle = "Range left, in the car's units. The car's own warning counts too.",
        options = (LowFuel.CHOICES + lowAt).distinct().sorted().map { LowFuelAt(it, "$it $unit") },
        selected = LowFuelAt(lowAt, "$lowAt $unit"),
        onSelect = {
            logWarn("[UI_ACTION] Low fuel warning at -> ${it.value} $unit", tag = "UI")
            store.setLowFuelAt(it.value)
        },
    )
}

/** One of Low fuel warning at's ranges, with its unit for the button. */
private data class LowFuelAt(
    val value: Int,
    override val label: String,
) : Labelled

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
    // Range in the car's distance unit, as the Fuel card shows it; the temperature follows the speed unit, as the clock's does.
    val miles = readings.distanceImperial(Locale.getDefault())
    val set: (VehicleStatusValues.Shares, Boolean) -> Unit = { next, needsEnergy ->
        logWarn("[UI_ACTION] Vehicle shares -> $next", tag = "UI")
        store.setShares(next)
        if (needsEnergy) onEnergyNeeded()
    }
    SettingsGroup {
        SwitchRow(
            title = "Range",
            subtitle = VehicleText.range(readings, miles, energyAllowed),
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
