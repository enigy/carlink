package com.carlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.carlink.BuildConfig
import com.carlink.car.DrivingState
import com.carlink.fuel.FuelStations
import com.carlink.fuel.GooglePlacesStore
import com.carlink.fuel.MonthlyBudget
import com.carlink.logging.logWarn
import kotlinx.coroutines.launch

/**
 * Settings › Vehicle › Price source (Nearby stations on): Free (OpenStreetMap, no prices), Google Places with the
 * driver's own key (stage C of the 11 Nov brief), or made-up stations in a debug build; and the search radius, in the
 * car's unit. With Google, the key's rows ([GoogleKeyRows]).
 */
@Composable
internal fun PriceSourceGroup(
    store: VehicleStore,
    imperial: Boolean,
) {
    val source by store.stationSource.collectAsState()
    val radiusMi by store.searchRadiusMi.collectAsState()
    SettingsGroup {
        ChoiceSetting(
            title = "Price source",
            subtitle = if (source == StationSource.GOOGLE) "Google sends prices, from your own Google Cloud key" else "OpenStreetMap has stations, without prices",
            options = StationSource.offered(BuildConfig.DEBUG),
            selected = source,
            onSelect = {
                logWarn("[UI_ACTION] Price source -> ${it.code}", tag = "UI")
                store.setStationSource(it)
            },
        )
        GroupDivider()
        ChoiceSetting(
            title = "Search radius",
            options = VehicleStore.RADII_MI.map { Radius(it, VehicleText.radius(it, imperial)) },
            selected = Radius(radiusMi, VehicleText.radius(radiusMi, imperial)),
            onSelect = {
                logWarn("[UI_ACTION] Search radius -> ${it.miles} mi", tag = "UI")
                store.setSearchRadiusMi(it.miles)
            },
        )
        if (source == StationSource.GOOGLE) {
            GroupDivider()
            GoogleKeyRows()
        }
    }
}

/** One of Search radius's choices: its miles, and its label in the car's unit. */
private data class Radius(
    val miles: Int,
    override val label: String,
) : Labelled

/**
 * The driver's Google key, parked only: enter it, test it with one lookup, or remove it (after a question: typing 39
 * characters again on the head unit is slow); this month's lookups against the cap; and what Google needs. The key is
 * shown only by its last four characters and never logged. A key can't come from a file: Android Automotive has no file
 * picker (its OPEN_DOCUMENT is a stub that says so, the Mac's 11 Nov bench).
 */
@Composable
private fun GoogleKeyRows() {
    val context = LocalContext.current
    val keys = remember(context) { GooglePlacesStore.get(context) }
    val hasKey by keys.hasKey.collectAsState()
    val budget by keys.budget.collectAsState()
    val parked by DrivingState.parked.collectAsState()
    var entering by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    KeyRow(keys.keyTail.takeIf { hasKey }, parked) { entering = true }
    GroupDivider()
    TestRow(hasKey, parked, result, onResult = { result = it }) { removing = true }
    GroupDivider()
    LookupCapRow(keys, budget)
    Text(
        "Google needs your own Google Cloud project with Places API (New) turned on and billing set up; restrict the key to " +
            "Places API (New). Google bills fuel prices at about \$40 per 1,000 lookups after about 1,000 free a month, " +
            "so the default limit stays under that. Check Google's pricing page.",
        style = SettingsStyle.Body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 88.dp, end = 32.dp, bottom = 24.dp),
    )
    if (entering) {
        KeyDialog(
            onSave = { key ->
                keys.setKey(key)
                logWarn("[UI_ACTION] Google key entered (${key.length} characters)", tag = "UI")
                entering = false
                result = null
            },
            onDismiss = { entering = false },
        )
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove the Google key?") },
            text = { Text("The Fuel card goes back to free stations, without prices, until you enter a key again.") },
            confirmButton = {
                TextButton(onClick = {
                    logWarn("[UI_ACTION] Google key removed", tag = "UI")
                    keys.clearKey()
                    removing = false
                    result = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Keep it") } },
        )
    }
}

/** The key's row: set (its last four characters) or not, and Enter, parked only. */
@Composable
private fun KeyRow(
    tail: String?,
    parked: Boolean,
    onEnter: () -> Unit,
) {
    val subtitle =
        when {
            tail != null -> "Set, ending $tail"
            parked -> "Not set"
            else -> "Not set. Park to enter one."
        }
    SettingRow(title = "Google API key", subtitle = subtitle) {
        RowAction("Enter", enabled = parked, onClick = onEnter)
    }
}

/** One lookup with the key, its answer in words ([result]), and Remove. */
@Composable
private fun TestRow(
    hasKey: Boolean,
    parked: Boolean,
    result: String?,
    onResult: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingRow(title = "Test the key", subtitle = result ?: "One lookup where the car is. It counts toward this month's lookups.") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RowAction("Test", enabled = parked && hasKey, onClick = {
                onResult("Asking Google…")
                scope.launch { onResult(FuelStations.testGoogle(context)) }
            })
            if (hasKey) RowAction("Remove", enabled = parked, weight = ActionWeight.CAUTION, onClick = onRemove)
        }
    }
}

/** This month's lookups, and the cap where the card stops asking Google and shows the free stations. */
@Composable
private fun LookupCapRow(
    keys: GooglePlacesStore,
    budget: MonthlyBudget,
) {
    val used = budget.used(GooglePlacesStore.thisMonth())
    ChoiceSetting(
        title = "Monthly lookup limit",
        subtitle = "$used ${if (used == 1) "lookup" else "lookups"} this month. At the limit the card shows free stations, without prices.",
        options = (MonthlyBudget.CAPS + budget.cap).distinct().sorted().map { Cap(it) },
        selected = Cap(budget.cap),
        onSelect = {
            logWarn("[UI_ACTION] Google lookup limit -> ${it.value}", tag = "UI")
            keys.setCap(it.value)
        },
    )
}

private data class Cap(
    val value: Int,
) : Labelled {
    override val label: String get() = "%,d".format(value)
}

/**
 * The key typed in: one line, trimmed. A password field, which keyboards don't learn from, hidden unless the driver asks
 * to see it (the Mac's 11 Nov bench).
 */
@Composable
private fun KeyDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Google API key") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.trim() },
                singleLine = true,
                label = { Text("Key") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide" else "Show") } },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.length >= MIN_KEY) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MIN_KEY = 20
