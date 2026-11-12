package com.carlink.ui.settings

/**
 * Settings › Vehicle › Fuel grade, for a gasoline car: the car reports unleaded but not which grade. [REGULAR] is the
 * default; [E85] is offered only to a car that lists E85 among its fuels. [google] is the grade's name in Google Places'
 * `fuelOptions.fuelPrices[].type` (the Fuel card's prices, stage C). Persisted by [code].
 */
enum class FuelGrade(
    override val label: String,
    val code: String,
    val google: String,
) : Labelled {
    REGULAR("Regular", "regular", "REGULAR_UNLEADED"),
    MIDGRADE("Midgrade", "midgrade", "MIDGRADE"),
    PREMIUM("Premium", "premium", "PREMIUM"),
    E85("E85", "e85", "E85"),
    ;

    companion object {
        /** The stored [code]'s grade; an unknown or missing one is [REGULAR]. */
        fun of(code: String?): FuelGrade = entries.firstOrNull { it.code == code } ?: REGULAR
    }
}
