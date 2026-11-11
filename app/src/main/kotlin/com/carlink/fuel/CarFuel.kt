package com.carlink.fuel

import com.carlink.ui.settings.EngineChoice
import com.carlink.ui.settings.FuelGrade

/**
 * What the truck fills up with, for the Fuel card and the stations it looks for: diesel, gasoline at a [grade], electric
 * (range only: charging is out of scope), or not known yet. Plain Kotlin, so it is tested without a car.
 */
data class CarFuel(
    val kind: Kind,
    val grade: FuelGrade? = null,
) {
    enum class Kind { DIESEL, GASOLINE, ELECTRIC, UNKNOWN }

    /** Whether the card looks for stations for it: diesel or gasoline. */
    val hasStations: Boolean get() = kind == Kind.DIESEL || kind == Kind.GASOLINE

    /** Its name in Google Places' `fuelOptions.fuelPrices[].type`, or null where it has none. */
    val googleType: String?
        get() =
            when (kind) {
                Kind.DIESEL -> "DIESEL"
                Kind.GASOLINE -> (grade ?: FuelGrade.REGULAR).google
                Kind.ELECTRIC, Kind.UNKNOWN -> null
            }

    /** The card's words for it: "Diesel", "Regular gasoline", "E85", "Electric". */
    val label: String
        get() =
            when (kind) {
                Kind.DIESEL -> "Diesel"
                Kind.GASOLINE -> if (grade == FuelGrade.E85) "E85" else "${(grade ?: FuelGrade.REGULAR).label} gasoline"
                Kind.ELECTRIC -> "Electric"
                Kind.UNKNOWN -> "Fuel type not read from the car yet"
            }

    companion object {
        // android.car.FuelType: the values INFO_FUEL_TYPE reports.
        private const val UNLEADED = 1
        private const val LEADED = 2
        private const val DIESEL_1 = 3
        private const val DIESEL_2 = 4
        private const val BIODIESEL = 5
        private const val E85 = 6
        private const val ELECTRIC = 10
        private val DIESELS = setOf(DIESEL_1, DIESEL_2, BIODIESEL)
        private val GASOLINES = setOf(UNLEADED, LEADED, E85)

        /**
         * The fuel for [fuelTypes] (INFO_FUEL_TYPE): diesel types (3, 4, 5) are diesel; unleaded (1, 2) or E85 (6) are
         * gasoline at the driver's [grade]; electric alone (10) is electric. The driver's [engine] override in Settings ›
         * Vehicle wins over what the car reports. A grade the car can't take (E85 on a car without it) is regular.
         */
        fun of(
            fuelTypes: List<Int>?,
            engine: EngineChoice,
            grade: FuelGrade,
        ): CarFuel {
            val types = fuelTypes.orEmpty()
            val kind =
                when (engine) {
                    EngineChoice.DIESEL -> Kind.DIESEL
                    EngineChoice.GASOLINE, EngineChoice.PLUG_IN_HYBRID -> Kind.GASOLINE
                    EngineChoice.ELECTRIC -> Kind.ELECTRIC
                    EngineChoice.CAR -> reported(types)
                }
            if (kind != Kind.GASOLINE) return CarFuel(kind)
            return CarFuel(kind, if (grade in grades(fuelTypes)) grade else FuelGrade.REGULAR)
        }

        /** What the car's own fuel types say: diesel first, then gasoline, then electric alone. */
        private fun reported(types: List<Int>): Kind =
            when {
                types.any { it in DIESELS } -> Kind.DIESEL
                types.any { it in GASOLINES } -> Kind.GASOLINE
                types == listOf(ELECTRIC) -> Kind.ELECTRIC
                else -> Kind.UNKNOWN
            }

        /** The grades Settings › Vehicle offers a gasoline car: regular, midgrade and premium, and E85 where it lists E85. */
        fun grades(fuelTypes: List<Int>?): List<FuelGrade> = if (fuelTypes.orEmpty().contains(E85)) FuelGrade.entries else FuelGrade.entries - FuelGrade.E85
    }
}
