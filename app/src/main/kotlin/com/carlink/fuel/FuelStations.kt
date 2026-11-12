package com.carlink.fuel

import android.content.Context
import android.os.SystemClock
import com.carlink.BuildConfig
import com.carlink.car.CarGpsFeed
import com.carlink.car.VehicleReadings
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.settings.FuelGrade
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
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The Fuel card's stations: looked up from the source Settings › Vehicle › Price source picks, when [LookupThrottle] says
 * so, and kept in memory only. Google Places needs the driver's key and room in the month's lookups ([GooglePlacesStore]);
 * without either, and for an hour after Google says the key's quota is used up ([GoogleQuota]) or for as long as it refuses
 * the key ([GoogleKeyProblem]), the card shows the free stations and says why ([View.notice]). A failed lookup keeps the last list with
 * its age. A busy service ([SourceBusy]) is tried again while the card is up, 30 s later and doubling to 5 min, or after
 * its own Retry-After. Logs carry counts, timings, HTTP statuses and sizes, never a position, a station's name or
 * address, or the key: a list of stations near home says where the driver lives.
 */
object FuelStations {
    enum class Status { OFF, NEEDS_LOCATION, NO_POSITION, LOOKING, READY, BUSY, OFFLINE, FAILED }

    /**
     * What the card shows: the [status], the last [stations] and when they came ([fetchedAt], elapsedRealtime), the
     * source's [attribution], and why the free stations stand in for Google's ([notice]).
     */
    data class View(
        val status: Status = Status.OFF,
        val stations: List<Station> = emptyList(),
        val fetchedAt: Long? = null,
        val attribution: String? = null,
        val notice: String? = null,
    )

    private const val MS_PER_S = 1_000L
    private const val GOOGLE_PAUSE_MS = 60 * 60_000L
    private const val QUOTA_NOTICE = "Google says this key's quota is used up for now. Free stations for an hour, without prices."

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val throttle = LookupThrottle()
    private val viewState = MutableStateFlow(View())
    private val hereState = MutableStateFlow<GeoPoint?>(null)
    private val overpass by lazy { OverpassSource() }
    private val demo by lazy { DemoSource() }

    @Volatile private var google: GooglePlacesSource? = null

    @Volatile private var visible = false

    // The lookup asking now. Launched on the main thread only (lookUpIfDue, retryAfter), so two never run at once.
    @Volatile private var looking: Job? = null

    // The pick as the last lookup left it; a different one (a setting changed, Google's hour ran out) looks up again at once.
    @Volatile private var lastPick: Pick? = null

    // Google out of quota (HTTP 429) for this key version until then, elapsedRealtime: free stations meanwhile.
    @Volatile private var googlePause: Pair<Int, Long>? = null

    // Google refused this key version, in the driver's words: free stations until the key changes.
    @Volatile private var googleProblem: Pair<Int, String>? = null

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
        // A retry left waiting while the card was hidden doesn't come back by itself: ask again as it returns.
        if (visible && viewState.value.status == Status.BUSY) throttle.reset()
        lookUpIfDue(app, lowFuelRose = false)
    }

    /** Low fuel just came on: one lookup, nearest first. */
    fun onLowFuel(context: Context) = lookUpIfDue(context.applicationContext, lowFuelRose = true)

    /**
     * Settings' Test for the driver's Google key: one lookup at the head unit's last position, in words. It counts toward
     * the month's lookups like any other.
     */
    suspend fun testGoogle(context: Context): String =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val keys = GooglePlacesStore.get(app)
            val here = hereState.value ?: FuelLocation.lastFix(app)
            val blocked =
                when {
                    !keys.hasKey.value -> "Enter a key first."
                    !keys.allowsLookup() -> "This month's lookup limit is reached."
                    here == null -> "Carlink doesn't know where the car is yet. Allow the location, then try again."
                    else -> null
                }
            if (blocked != null || here == null) return@withContext blocked.orEmpty()
            val store = VehicleStore.get(app)
            val fuel = carFuel(store).takeIf { it.hasStations } ?: CarFuel(CarFuel.Kind.GASOLINE, FuelGrade.REGULAR)
            keys.spendLookup()
            try {
                val stations = google(app).stationsNear(here, fuel, radiusM(store))
                logInfo("[FUEL] Google Places test: ${stations.size} stations (${stations.count { it.price != null }} priced)", tag = Logger.Tags.ADAPTR)
                "It works: ${stations.size} stations nearby, ${stations.count { it.price != null }} with a ${fuel.label.lowercase()} price."
            } catch (e: GoogleKeyProblem) {
                e.words
            } catch (e: GoogleQuota) {
                "Google says this key's quota is used up for now (${e.why}). Raise the quota in Google Cloud, or wait."
            } catch (e: SourceBusy) {
                "Google is busy (${e.why}). Try again in a minute."
            } catch (_: SourceUnreachable) {
                "No internet."
            } catch (e: IOException) {
                "Couldn't read Google's answer (${e.javaClass.simpleName})."
            }
        }

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
        val fuel = carFuel(store)
        if (!fuel.hasStations) return
        val pick = pick(app, store)
        if (pick != lastPick) {
            throttle.reset()
            lastPick = pick
            // A lookup still asking went by the old pick: look again once it ends. Otherwise a change made during a 30 s
            // Overpass lookup (Price source, the key typed in) waited for the next fix, which never comes parked (the Mac's
            // 11 Nov bench).
            val running = looking
            if (running?.isActive == true) {
                scope.launch(Dispatchers.Main) {
                    running.join()
                    lookUpIfDue(app, lowFuelRose = false)
                }
            }
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
        looking = scope.launch { lookUp(app, pick, here, fuel) }
    }

    private fun lookUp(
        app: Context,
        pick: Pick,
        here: GeoPoint,
        fuel: CarFuel,
    ) {
        val source = pick.source
        if (source is GooglePlacesSource) GooglePlacesStore.get(app).spendLookup()
        val started = SystemClock.elapsedRealtime()
        val outcome = runCatching { source.stationsNear(here, fuel, pick.radiusMi * METRES_PER_MILE) }
        val ms = SystemClock.elapsedRealtime() - started
        googleAside(outcome.exceptionOrNull(), pick.keyVersion)
        // What the lookup changed itself (Google set aside, the month's last lookup spent) is no new pick for the next fix:
        // before, each fallback to the free stations was looked up a second time (the Mac's 11 Nov bench).
        val next = pick(app, VehicleStore.get(app))
        lastPick = next
        // The instance asked, also on a busy or failed line: the log shows which one timed out (the Mac's 11 Nov bench).
        val from = source.detail?.let { " ($it)" }.orEmpty()
        val stations = outcome.getOrNull()
        if (stations != null) {
            busyInARow = 0
            viewState.value = View(Status.READY, stations, SystemClock.elapsedRealtime(), source.attribution, pick.notice)
            logInfo("[FUEL] ${source.name}$from: ${stations.size} stations (${stations.count { it.price != null }} priced) in $ms ms", tag = Logger.Tags.ADAPTR)
            return
        }
        val e = outcome.exceptionOrNull() ?: return
        if (e is GoogleQuota || e is GoogleKeyProblem) {
            logWarn("[FUEL] Google Places: ${e.message} after $ms ms; free stations meanwhile", tag = Logger.Tags.ADAPTR)
            lookUp(app, next, here, fuel)
            return
        }
        val status =
            when (e) {
                is SourceBusy -> Status.BUSY
                is SourceUnreachable -> Status.OFFLINE
                // A bad answer (IOException, JSONException), or a bug in reading it: the card says it couldn't, the app goes on.
                else -> Status.FAILED
            }
        viewState.value = viewState.value.copy(status = status, notice = pick.notice)
        val why = (e as? SourceBusy)?.why ?: (e as? SourceUnreachable)?.why ?: e.message ?: e.javaClass.simpleName
        if (e !is SourceBusy) {
            logWarn("[FUEL] ${source.name}$from: $status after $ms ms ($why)", tag = Logger.Tags.ADAPTR)
            return
        }
        val waitS = RetryWait.seconds(++busyInARow, e.retryAfterS)
        logWarn("[FUEL] ${source.name}$from: busy ($why) after $ms ms; trying again in $waitS s", tag = Logger.Tags.ADAPTR)
        retry = scope.launch { retryAfter(app, waitS) }
    }

    /**
     * A busy service, asked again after [waitS] while the card is still up and Nearby stations on. Launched from the main
     * thread as the running lookup, like [lookUpIfDue]'s: a change made while it asks waits for it, never runs beside it.
     */
    private suspend fun retryAfter(
        app: Context,
        waitS: Long,
    ) {
        delay(waitS * MS_PER_S)
        val store = VehicleStore.get(app)
        val here = hereState.value
        if (!visible || here == null) return
        if (!store.nearbyStations.value || !CarGpsFeed.permitted(app)) return
        withContext(Dispatchers.Main) {
            if (looking?.isActive != true) {
                throttle.ran(SystemClock.elapsedRealtime(), here)
                looking = scope.launch { lookUp(app, pick(app, store), here, carFuel(store)) }
            }
        }
    }

    /** The source to ask and why it isn't the one picked ([notice]), with the radius and the Google key it goes by. */
    private data class Pick(
        val source: FuelPriceSource,
        val notice: String?,
        val radiusMi: Int,
        val keyVersion: Int,
    )

    private fun pick(
        app: Context,
        store: VehicleStore,
    ): Pick {
        val keys = GooglePlacesStore.get(app)
        val version = keys.keyVersion.value
        val paused = googlePause?.let { (v, until) -> v == version && SystemClock.elapsedRealtime() < until } == true
        val (source, notice) =
            when (store.stationSource.value) {
                StationSource.DEMO -> (if (BuildConfig.DEBUG) demo else overpass) to null
                StationSource.GOOGLE ->
                    when {
                        !keys.hasKey.value -> overpass to "No Google key yet: free stations, without prices"
                        googleProblem?.first == version -> overpass to "${googleProblem?.second} Free stations meanwhile, without prices."
                        paused -> overpass to QUOTA_NOTICE
                        !keys.allowsLookup() -> overpass to "Monthly lookup limit reached: free stations, without prices"
                        else -> google(app) to null
                    }
                StationSource.FREE -> overpass to null
            }
        return Pick(source, notice, store.searchRadiusMi.value, version)
    }

    /**
     * Google out of quota (an hour aside) or refusing the key (aside until it changes), for the key version the lookup
     * used: a key entered while it was asked is tried, not set aside with the old one. A new key ends the hour too.
     */
    private fun googleAside(
        e: Throwable?,
        keyVersion: Int,
    ) {
        if (e is GoogleQuota) googlePause = keyVersion to SystemClock.elapsedRealtime() + GOOGLE_PAUSE_MS
        if (e is GoogleKeyProblem) googleProblem = keyVersion to e.words
    }

    private fun google(app: Context): GooglePlacesSource =
        google ?: GooglePlacesSource(
            key = { GooglePlacesStore.get(app).key() },
            appHeaders = GooglePlacesSource.appHeaders(app),
        ).also { google = it }
}

/** The fuel the card looks for: the car's, the driver's engine override and grade. */
private fun carFuel(store: VehicleStore) = CarFuel.of(VehicleReadings.state.value.fuelTypes, store.engineChoice.value, store.fuelGrade.value)

/** How far the card looks, Settings › Vehicle › Search radius. */
private fun radiusM(store: VehicleStore) = store.searchRadiusMi.value * METRES_PER_MILE

private const val METRES_PER_MILE = 1609.344

/**
 * How long to wait before asking a busy service again: 30 s, doubling to 5 min, and never less than its own Retry-After
 * (up to an hour).
 */
object RetryWait {
    private const val FIRST_S = 30L
    private const val MAX_S = 300L
    private const val MAX_DOUBLINGS = 4
    private const val MAX_RETRY_AFTER_S = 3_600L

    fun seconds(
        busyInARow: Int,
        retryAfterS: Long?,
    ): Long {
        val backoff = (FIRST_S shl (busyInARow - 1).coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(MAX_S)
        return maxOf(backoff, (retryAfterS ?: 0L).coerceAtMost(MAX_RETRY_AFTER_S))
    }
}
