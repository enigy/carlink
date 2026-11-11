package com.carlink.fuel

import android.content.Context
import android.os.SystemClock
import com.carlink.BuildConfig
import com.carlink.car.CarGpsFeed
import com.carlink.car.VehicleReadings
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.settings.StationSource
import com.carlink.ui.settings.VehicleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException

/**
 * The Fuel card's stations: looked up from the source Settings › Vehicle picks, when [LookupThrottle] says so, and kept
 * in memory only. A failed lookup keeps the last list with its age. Logs carry counts, timings and sizes, never a
 * position, a station's name or an address: a list of stations near home says where the driver lives.
 */
object FuelStations {
    enum class Status { OFF, NEEDS_LOCATION, NO_POSITION, LOOKING, READY, OFFLINE, FAILED }

    /** What the card shows: the [status], the last [stations] and when they came ([fetchedAt], elapsedRealtime). */
    data class View(
        val status: Status = Status.OFF,
        val stations: List<Station> = emptyList(),
        val fetchedAt: Long? = null,
        val attribution: String? = null,
    )

    /** How far the card looks: 5 miles. */
    const val RADIUS_M = 8_046.72

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val throttle = LookupThrottle()
    private val viewState = MutableStateFlow(View())
    private val hereState = MutableStateFlow<GeoPoint?>(null)
    private val overpass by lazy { OverpassSource() }
    private val demo by lazy { DemoSource() }

    @Volatile private var visible = false

    @Volatile private var looking: Job? = null

    @Volatile private var sourceName: String? = null

    val view: StateFlow<View> = viewState.asStateFlow()

    /** Where the head unit is, for the card's distances; null until a fix comes. */
    val here: StateFlow<GeoPoint?> = hereState.asStateFlow()

    /** The card came up or went ([visible]), or a setting it hangs on changed: Nearby stations, the location permission. */
    fun onCard(
        context: Context,
        visible: Boolean,
    ) {
        val app = context.applicationContext
        this.visible = visible
        val store = VehicleStore.get(app)
        val off =
            when {
                !store.nearbyStations.value -> Status.OFF
                !CarGpsFeed.permitted(app) -> Status.NEEDS_LOCATION
                else -> null
            }
        if (off != null) {
            FuelLocation.stop()
            throttle.reset()
            viewState.value = View(off)
            return
        }
        if (visible) FuelLocation.start(app) { onPosition(app, it) } else FuelLocation.stop()
        lookUpIfDue(app, lowFuelRose = false)
    }

    /** Low fuel just came on: one lookup, nearest first. */
    fun onLowFuel(context: Context) = lookUpIfDue(context.applicationContext, lowFuelRose = true)

    private fun onPosition(
        app: Context,
        fix: GeoPoint,
    ) {
        hereState.value = fix
        lookUpIfDue(app, lowFuelRose = false)
    }

    private fun lookUpIfDue(
        app: Context,
        lowFuelRose: Boolean,
    ) {
        val store = VehicleStore.get(app)
        if (!store.nearbyStations.value || !CarGpsFeed.permitted(app)) return
        val fuel = CarFuel.of(VehicleReadings.state.value.fuelTypes, store.engineChoice.value, store.fuelGrade.value)
        if (!fuel.hasStations) return
        val source = if (BuildConfig.DEBUG && store.stationSource.value == StationSource.DEMO) demo else overpass
        if (source.name != sourceName) {
            throttle.reset()
            sourceName = source.name
        }
        val here = hereState.value
        if (here == null) {
            if (visible && viewState.value.stations.isEmpty()) viewState.value = View(Status.NO_POSITION)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (looking?.isActive == true || !throttle.due(now, here, visible, lowFuelRose)) return
        throttle.ran(now, here)
        viewState.value = viewState.value.copy(status = Status.LOOKING)
        looking = scope.launch { lookUp(source, here, fuel, now) }
    }

    private fun lookUp(
        source: FuelPriceSource,
        here: GeoPoint,
        fuel: CarFuel,
        started: Long,
    ) {
        val failed =
            try {
                val stations = source.stationsNear(here, fuel, RADIUS_M)
                viewState.value = View(Status.READY, stations, SystemClock.elapsedRealtime(), source.attribution)
                logInfo("[FUEL] ${source.name}: ${stations.size} stations in ${SystemClock.elapsedRealtime() - started} ms", tag = Logger.Tags.ADAPTR)
                null
            } catch (e: IOException) {
                Status.OFFLINE to e
            } catch (e: JSONException) {
                Status.FAILED to e
            }
        failed?.let { (status, e) ->
            viewState.value = viewState.value.copy(status = status)
            logWarn("[FUEL] ${source.name}: no stations after ${SystemClock.elapsedRealtime() - started} ms (${e.javaClass.simpleName})", tag = Logger.Tags.ADAPTR)
        }
    }
}
