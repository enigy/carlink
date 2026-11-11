package com.carlink.ui.settings

import android.content.Context
import android.content.SharedPreferences
import com.carlink.car.VehicleStatusValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings › Vehicle: what the truck gives the iPhone. Car GPS for the iPhone, on by default, sends the head unit's
 * GPS to help the phone's own ([com.carlink.car.CarGpsFeed]). The data points (fuel type, range, the low-fuel warning,
 * the outside temperature) are shared one by one ([VehicleStatusValues.Shares]), off by default until a bench shows
 * the phone takes them. The truck's, not a driver's: one file for every iPhone. Changes apply when the phone next
 * connects, since the adapter declares them as the session starts.
 */
class VehicleStore private constructor(
    context: Context,
) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gpsState = MutableStateFlow(prefs.getBoolean(KEY_GPS, true))
    private val sharesState = MutableStateFlow(readShares())

    /** Whether the head unit's GPS goes to the phone. */
    val carGps: StateFlow<Boolean> = gpsState.asStateFlow()

    /** Which data points go to the phone. */
    val shares: StateFlow<VehicleStatusValues.Shares> = sharesState.asStateFlow()

    fun setCarGps(on: Boolean) {
        prefs.edit().putBoolean(KEY_GPS, on).apply()
        gpsState.value = on
    }

    fun setShares(s: VehicleStatusValues.Shares) {
        prefs
            .edit()
            .putBoolean(KEY_FUEL_TYPE, s.fuelType)
            .putBoolean(KEY_RANGE, s.range)
            .putBoolean(KEY_LOW_FUEL, s.lowFuel)
            .putBoolean(KEY_OUTSIDE, s.outsideTemperature)
            .apply()
        sharesState.value = s
    }

    private fun readShares() =
        VehicleStatusValues.Shares(
            fuelType = prefs.getBoolean(KEY_FUEL_TYPE, false),
            range = prefs.getBoolean(KEY_RANGE, false),
            lowFuel = prefs.getBoolean(KEY_LOW_FUEL, false),
            outsideTemperature = prefs.getBoolean(KEY_OUTSIDE, false),
        )

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these orphans the stored choice.
        private const val PREFS_NAME = "carlink_vehicle"
        private const val KEY_GPS = "car_gps_for_phone"
        private const val KEY_FUEL_TYPE = "share_fuel_type"
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
