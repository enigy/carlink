package com.carlink.ui.pane

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.carlink.car.VehicleReadings
import com.carlink.fuel.LowFuel
import com.carlink.logging.logInfo
import com.carlink.ui.settings.VehicleStore
import java.util.Locale

/**
 * Low fuel ([LowFuel]: the car's warning, or the range at Settings › Vehicle › Low fuel warning at) with Show on low
 * fuel on.
 */
@Composable
internal fun rememberLowFuel(): Boolean {
    val context = LocalContext.current
    val store = remember(context) { VehicleStore.get(context) }
    val onLow by store.fuelOnLow.collectAsState()
    val at by store.lowFuelAt.collectAsState()
    val vehicle by VehicleReadings.state.collectAsState()
    return onLow && LowFuel.isLow(vehicle, at, vehicle.distanceImperial(Locale.getDefault()))
}

/** The deck has the Fuel card: on a head unit (a car to read it from), with Show the Fuel card on or [lowFuel]. */
@Composable
internal fun rememberFuelDealt(lowFuel: Boolean): Boolean {
    val context = LocalContext.current
    val store = remember(context) { VehicleStore.get(context) }
    val automotive = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) }
    val on by store.fuelCard.collectAsState()
    return automotive && (on || lowFuel)
}

/**
 * The pane follows the low-fuel warning ([LowFuelDeck]): the Fuel card comes up as it comes on, also when the pane
 * appears with it on, and the card it replaced comes back as it goes off.
 */
@Composable
internal fun FollowLowFuel(
    lowFuel: Boolean,
    pages: List<PaneCard>,
    shown: PaneCard,
    show: (PaneCard) -> Unit,
) {
    var before by remember { mutableStateOf<PaneCard?>(null) }
    val pagesNow by rememberUpdatedState(pages)
    val shownNow by rememberUpdatedState(shown)
    LaunchedEffect(lowFuel) {
        val next =
            if (lowFuel) {
                before = shownNow
                LowFuelDeck.onLow(pagesNow, shownNow)
            } else {
                LowFuelDeck.onCleared(before, shownNow, pagesNow).also { before = null }
            }
        if (next != shownNow) {
            logInfo("[UI_ACTION] Pane: low fuel ${if (lowFuel) "on" else "off"}, showing $next", tag = "UI")
            show(next)
        }
    }
}
