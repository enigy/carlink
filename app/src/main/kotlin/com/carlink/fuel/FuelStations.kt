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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Fuel card's stations: looked up from the source Settings › Vehicle picks, when [LookupThrottle] says so, and kept
 * in memory only. A failed lookup keeps the last list with its age. A busy service ([SourceBusy]) is tried again while
 * the card is up, 30 s later and doubling to 5 min, or after its own Retry-After. Logs carry counts, timings, HTTP
 * statuses and sizes, never a
 * position, a station's name or an address: a list of stations near home says where the driver lives.
 */
object FuelStations {
    enum class Status { OFF, NEEDS_LOCATION, NO_POSITION, LOOKING, READY, BUSY, OFFLINE, FAILED }

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

    @Volatile private var retry: Job? = null

    @Volatile private var busyInARow = 0

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
            retry?.cancel()
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
        val source = sourceFor(store)
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
        retry?.cancel()
        looking = scope.launch { lookUp(app, source, here, fuel) }
    }

    private fun lookUp(
        app: Context,
        source: FuelPriceSource,
        here: GeoPoint,
        fuel: CarFuel,
    ) {
        val started = SystemClock.elapsedRealtime()
        val outcome = runCatching { source.stationsNear(here, fuel, RADIUS_M) }
        val ms = SystemClock.elapsedRealtime() - started
        val stations = outcome.getOrNull()
        if (stations != null) {
            busyInARow = 0
            viewState.value = View(Status.READY, stations, SystemClock.elapsedRealtime(), source.attribution)
            logInfo("[FUEL] ${source.name}: ${stations.size} stations in $ms ms", tag = Logger.Tags.ADAPTR)
            return
        }
        val e = outcome.exceptionOrNull() ?: return
        val status =
            when (e) {
                is SourceBusy -> Status.BUSY
                is SourceUnreachable -> Status.OFFLINE
                // A bad answer (IOException, JSONException), or a bug in reading it: the card says it couldn't, the app goes on.
                else -> Status.FAILED
            }
        viewState.value = viewState.value.copy(status = status)
        val why = (e as? SourceBusy)?.why ?: (e as? SourceUnreachable)?.why ?: e.message ?: e.javaClass.simpleName
        if (e !is SourceBusy) {
            logWarn("[FUEL] ${source.name}: $status after $ms ms ($why)", tag = Logger.Tags.ADAPTR)
            return
        }
        val waitS = RetryWait.seconds(++busyInARow, e.retryAfterS)
        logWarn("[FUEL] ${source.name}: busy ($why) after $ms ms; trying again in $waitS s", tag = Logger.Tags.ADAPTR)
        retry = scope.launch { retryAfter(app, waitS) }
    }

    /** A busy service, asked again after [waitS] while the card is still up, Nearby stations on and the fuel the same. */
    private suspend fun retryAfter(
        app: Context,
        waitS: Long,
    ) {
        delay(waitS * MS_PER_S)
        val store = VehicleStore.get(app)
        val here = hereState.value
        if (!visible || here == null) return
        if (!store.nearbyStations.value || !CarGpsFeed.permitted(app)) return
        val fuel = CarFuel.of(VehicleReadings.state.value.fuelTypes, store.engineChoice.value, store.fuelGrade.value)
        throttle.ran(SystemClock.elapsedRealtime(), here)
        lookUp(app, sourceFor(store), here, fuel)
    }

    private fun sourceFor(store: VehicleStore): FuelPriceSource = if (BuildConfig.DEBUG && store.stationSource.value == StationSource.DEMO) demo else overpass

    private const val MS_PER_S = 1_000L
}

/** How long to wait before asking a busy service again: 30 s, doubling to 5 min, and never less than its Retry-After. */
object RetryWait {
    private const val FIRST_S = 30L
    private const val MAX_S = 300L
    private const val MAX_DOUBLINGS = 4

    fun seconds(
        busyInARow: Int,
        retryAfterS: Long?,
    ): Long {
        val backoff = (FIRST_S shl (busyInARow - 1).coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(MAX_S)
        return maxOf(backoff, retryAfterS ?: 0L).coerceAtMost(MAX_S)
    }
}
