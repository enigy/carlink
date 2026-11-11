package com.carlink.ui.settings

/**
 * Settings › Vehicle's engine type: the one the car reports ([CAR], the default), or one the driver picks for a car
 * that reports none or the wrong one. The iPhone always gets it ([com.carlink.car.VehicleStatusValues.declaredEngines]);
 * [engines] are Apple's names for the override. Persisted by [code].
 */
enum class EngineChoice(
    override val label: String,
    val code: String,
    val engines: List<String>,
) : Labelled {
    CAR("From the car", "car", emptyList()),
    GASOLINE("Gasoline", "gasoline", listOf("gasoline")),
    DIESEL("Diesel", "diesel", listOf("diesel")),
    ELECTRIC("Electric", "electric", listOf("electric")),
    PLUG_IN_HYBRID("Plug-in hybrid", "plug_in_hybrid", listOf("gasoline", "electric")),
    ;

    companion object {
        /** The stored [code]'s choice; an unknown or missing one is [CAR]. */
        fun of(code: String?): EngineChoice = entries.firstOrNull { it.code == code } ?: CAR
    }
}
