package com.carlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.carlink.car.DrivingState
import com.carlink.fuel.DiscountBrand
import com.carlink.fuel.FuelDiscount
import com.carlink.fuel.FuelDiscounts
import com.carlink.logging.logWarn

/**
 * Settings › Vehicle › Fuel discounts (Scott, 14 Nov): the station programs the driver is in, each with the driver's own
 * cents off. Every listed station starts as not a member, with no amount; another station can be added by name. Only
 * that the driver is in a program and its amount are kept, never a membership number. Entered parked, as the Google key
 * is. The Fuel card then prices and ranks those stations at what the driver pays.
 */
@Composable
internal fun FuelDiscountsGroup(store: VehicleStore) {
    val discounts by store.fuelDiscounts.collectAsState()
    val parked by DrivingState.parked.collectAsState()
    var editing by remember { mutableStateOf<FuelDiscount?>(null) }
    val others = discounts.filter { it.brand == null }
    SettingsGroup {
        DiscountBrand.entries.forEachIndexed { i, brand ->
            if (i > 0) GroupDivider()
            val mine = discounts.firstOrNull { it.brand == brand }
            DiscountRow(brand.label, mine, parked) { editing = mine ?: FuelDiscount(brand, "", 0) }
        }
        others.forEach { d ->
            GroupDivider()
            DiscountRow(d.label, d, parked) { editing = d }
        }
        GroupDivider()
        SettingRow(title = "Another station", subtitle = if (parked) "A program at a station not listed above" else "Park to add one") {
            RowAction("Add", enabled = parked, onClick = { editing = FuelDiscount(null, "", 0) })
        }
    }
    Text(
        "Cents off each gallon at a station whose program you're in, as the program gives it. The Fuel card shows what you " +
            "pay there, with the station's own price beside it.",
        style = SettingsStyle.Body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 88.dp, end = 32.dp, bottom = 24.dp),
    )
    editing?.let { start ->
        DiscountDialog(
            start = start,
            onSave = { saved ->
                // Keyed by the brand, or the name for another station; 0 cents is "not a member" and drops it.
                val rest = discounts.filterNot { it.sameProgram(start) || it.sameProgram(saved) }
                store.setFuelDiscounts(if (saved.cents > 0) rest + saved else rest)
                logWarn("[UI_ACTION] Fuel discount ${saved.label} -> ${if (saved.cents > 0) FuelDiscounts.describe(saved) else "none"}", tag = "UI")
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private fun FuelDiscount.sameProgram(other: FuelDiscount): Boolean =
    if (brand != null) brand == other.brand else other.brand == null && name.trim().equals(other.name.trim(), ignoreCase = true)

/** One program: not a member, or its amount; Set, parked only. */
@Composable
private fun DiscountRow(
    title: String,
    mine: FuelDiscount?,
    parked: Boolean,
    onSet: () -> Unit,
) {
    SettingRow(title = title, subtitle = mine?.let { FuelDiscounts.describe(it) } ?: "Not a member") {
        RowAction("Set", enabled = parked, onClick = onSet)
    }
}

/** The amount (blank: not a member), diesel too or not, and for another station its name. */
@Composable
private fun DiscountDialog(
    start: FuelDiscount,
    onSave: (FuelDiscount) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(start.name) }
    var cents by remember { mutableStateOf(if (start.cents > 0) start.cents.toString() else "") }
    var allFuels by remember { mutableStateOf(start.allFuels) }
    val amount = cents.toIntOrNull()
    val valid = (cents.isEmpty() || (amount != null && amount in 1..FuelDiscounts.MAX_CENTS)) && (start.brand != null || name.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(start.brand?.label ?: "Another station") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (start.brand == null) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(MAX_NAME) },
                        singleLine = true,
                        label = { Text("Station name, as the card shows it") },
                    )
                }
                OutlinedTextField(
                    value = cents,
                    onValueChange = { v -> cents = v.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    label = { Text("Cents off per gallon (blank: not a member)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Switch(checked = allFuels, onCheckedChange = { allFuels = it })
                    Text("Diesel too", style = SettingsStyle.Body)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(FuelDiscount(start.brand, name.trim(), amount ?: 0, allFuels)) }, enabled = valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAX_NAME = 40
