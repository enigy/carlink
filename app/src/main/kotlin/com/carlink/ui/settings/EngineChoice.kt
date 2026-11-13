package com.carlink.ui.settings

/**
 * Settings › Vehicle's engine type: the one the car reports ([CAR], the default: nothing picked), or one the driver picks
 * for a car that reports none or the wrong one. The selector offers only the four types ([choices]): while nothing is
 * picked it shows the car's own selected ([reported]), and a pick is remembered (Scott, 13 Nov). The iPhone always gets
 * it ([com.carlink.car.VehicleStatusValues.declaredEngines]); [engines] are Apple's names for the override. Persisted by
 * [code].
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

        /** What the selector offers: the four engine types, with no "From the car". */
        val choices: List<EngineChoice> get() = entries - CAR

        /** The choice whose engines are the ones the car reports (Apple's names, any order); null when none is. */
        fun reported(engines: List<String>): EngineChoice? = choices.firstOrNull { it.engines.toSet() == engines.toSet() }
    }
}
