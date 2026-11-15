package com.carlink.ui.settings

import android.content.Context
import android.content.SharedPreferences
import com.carlink.car.VehicleStatusValues
import com.carlink.fuel.FuelDiscount
import com.carlink.fuel.FuelDiscounts
import com.carlink.fuel.LowFuel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings › Vehicle: what the truck gives the iPhone. Car GPS for the iPhone, on by default, sends the head unit's
 * GPS to help the phone's own ([com.carlink.car.CarGpsFeed]). The engine type always goes, the car's own unless the
 * driver picks one ([EngineChoice]). The data points (range, the low-fuel warning, the outside temperature) are shared
 * one by one ([VehicleStatusValues.Shares]), off by default. The truck's, not a driver's: one file for every iPhone.
 * Changes apply when the phone next connects, since the adapter declares them as the session starts.
 * The Fuel card's own settings ([fuelCard], [fuelOnLow], [lowFuelAt], [fuelGrade], [nearbyStations], [stationSource],
 * [fuelDiscounts]) apply
 * at once.
 */
@Suppress("TooManyFunctions") // One setter per Vehicle setting, each persisted and published the same way.
class VehicleStore private constructor(
    context: Context,
) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gpsState = MutableStateFlow(prefs.getBoolean(KEY_GPS, true))
    private val sharesState =
        MutableStateFlow(
            VehicleStatusValues.Shares(
                range = prefs.getBoolean(KEY_RANGE, false),
                lowFuel = prefs.getBoolean(KEY_LOW_FUEL, false),
                outsideTemperature = prefs.getBoolean(KEY_OUTSIDE, false),
            ),
        )
    private val engineState = MutableStateFlow(EngineChoice.of(prefs.getString(KEY_ENGINE, null)))
    private val fuelCardState = MutableStateFlow(prefs.getBoolean(KEY_FUEL_CARD, true))
    private val fuelOnLowState = MutableStateFlow(prefs.getBoolean(KEY_FUEL_ON_LOW, true))
    private val fuelGradeState = MutableStateFlow(FuelGrade.of(prefs.getString(KEY_FUEL_GRADE, null)))
    private val lowFuelAtState = MutableStateFlow(prefs.getInt(KEY_LOW_FUEL_AT, LowFuel.DEFAULT_AT))
    private val nearbyState = MutableStateFlow(prefs.getBoolean(KEY_NEARBY, false))
    private val stationSourceState = MutableStateFlow(StationSource.of(prefs.getString(KEY_STATION_SOURCE, null)))
    private val radiusState = MutableStateFlow(prefs.getInt(KEY_RADIUS_MI, DEFAULT_RADIUS_MI))
    private val discountsState = MutableStateFlow(FuelDiscounts.decode(prefs.getString(KEY_FUEL_DISCOUNTS, null)))

    /** Whether the head unit's GPS goes to the phone. */
    val carGps: StateFlow<Boolean> = gpsState.asStateFlow()

    /** Which data points go to the phone. */
    val shares: StateFlow<VehicleStatusValues.Shares> = sharesState.asStateFlow()

    /** The engine type the phone gets: the car's, or the driver's pick. */
    val engineChoice: StateFlow<EngineChoice> = engineState.asStateFlow()

    /** The Fuel card in the side pane's deck (default on). */
    val fuelCard: StateFlow<Boolean> = fuelCardState.asStateFlow()

    /** The pane turns to the Fuel card when the low-fuel warning comes on (default on). */
    val fuelOnLow: StateFlow<Boolean> = fuelOnLowState.asStateFlow()

    /** A gasoline car's grade, which the car can't report (default regular). */
    val fuelGrade: StateFlow<FuelGrade> = fuelGradeState.asStateFlow()

    /** The range, in the car's distance unit, at which the Fuel card warns of low fuel ([LowFuel]; default 25). */
    val lowFuelAt: StateFlow<Int> = lowFuelAtState.asStateFlow()

    /** The Fuel card looks up stations nearby, sending the car's position to [stationSource] (default off). */
    val nearbyStations: StateFlow<Boolean> = nearbyState.asStateFlow()

    /** Where the stations come from: OpenStreetMap, Google Places with the driver's key, or made-up ones in a debug build. */
    val stationSource: StateFlow<StationSource> = stationSourceState.asStateFlow()

    /** How far the card looks for stations, miles (default 5); shown in the car's unit. */
    val searchRadiusMi: StateFlow<Int> = radiusState.asStateFlow()

    /** The discount programs the driver is in ([FuelDiscount]); none by default, and each amount the driver's own. */
    val fuelDiscounts: StateFlow<List<FuelDiscount>> = discountsState.asStateFlow()

    fun setCarGps(on: Boolean) {
        prefs.edit().putBoolean(KEY_GPS, on).apply()
        gpsState.value = on
    }

    fun setShares(s: VehicleStatusValues.Shares) {
        prefs
            .edit()
            .putBoolean(KEY_RANGE, s.range)
            .putBoolean(KEY_LOW_FUEL, s.lowFuel)
            .putBoolean(KEY_OUTSIDE, s.outsideTemperature)
            .apply()
        sharesState.value = s
    }

    fun setEngineChoice(choice: EngineChoice) {
        prefs.edit().putString(KEY_ENGINE, choice.code).apply()
        engineState.value = choice
    }

    fun setFuelCard(on: Boolean) {
        prefs.edit().putBoolean(KEY_FUEL_CARD, on).apply()
        fuelCardState.value = on
    }

    fun setFuelOnLow(on: Boolean) {
        prefs.edit().putBoolean(KEY_FUEL_ON_LOW, on).apply()
        fuelOnLowState.value = on
    }

    fun setFuelGrade(grade: FuelGrade) {
        prefs.edit().putString(KEY_FUEL_GRADE, grade.code).apply()
        fuelGradeState.value = grade
    }

    fun setLowFuelAt(at: Int) {
        prefs.edit().putInt(KEY_LOW_FUEL_AT, at).apply()
        lowFuelAtState.value = at
    }

    fun setNearbyStations(on: Boolean) {
        prefs.edit().putBoolean(KEY_NEARBY, on).apply()
        nearbyState.value = on
    }

    fun setStationSource(source: StationSource) {
        prefs.edit().putString(KEY_STATION_SOURCE, source.code).apply()
        stationSourceState.value = source
    }

    fun setSearchRadiusMi(miles: Int) {
        prefs.edit().putInt(KEY_RADIUS_MI, miles).apply()
        radiusState.value = miles
    }

    fun setFuelDiscounts(discounts: List<FuelDiscount>) {
        prefs.edit().putString(KEY_FUEL_DISCOUNTS, FuelDiscounts.encode(discounts)).apply()
        discountsState.value = discounts
    }

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these orphans the stored choice.
        private const val PREFS_NAME = "carlink_vehicle"
        private const val KEY_GPS = "car_gps_for_phone"
        private const val KEY_ENGINE = "engine_type"
        private const val KEY_FUEL_CARD = "fuel_card"
        private const val KEY_FUEL_ON_LOW = "fuel_card_on_low_fuel"
        private const val KEY_FUEL_GRADE = "fuel_grade"
        private const val KEY_LOW_FUEL_AT = "low_fuel_at_range"
        private const val KEY_NEARBY = "fuel_nearby_stations"
        private const val KEY_STATION_SOURCE = "fuel_station_source"
        private const val KEY_RADIUS_MI = "fuel_search_radius_mi"
        private const val KEY_FUEL_DISCOUNTS = "fuel_discounts"
        const val DEFAULT_RADIUS_MI = 5

        /** The radii Settings › Vehicle offers, miles. */
        val RADII_MI = listOf(2, 5, 10, 20)
        private const val KEY_RANGE = "share_range"
        private const val KEY_LOW_FUEL = "share_low_fuel"
        private const val KEY_OUTSIDE = "share_outside_temperature"

        @Volatile private var instance: VehicleStore? = null

        fun get(context: Context): VehicleStore =
            instance ?: synchronized(this) {
                instance ?: VehicleStore(context).also { instance = it }
            }
    }
}
