package com.carlink.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.carlink.BuildConfig
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ocbm.BenchConfigExtra
import org.json.JSONObject

/**
 * The head unit's own GPS for the iPhone: a debug build's bench experiment (LIVI research item 4), off by default.
 *
 * The adapter declares the car's location to the phone only when this build pushed `location:` (a [BenchConfigExtra]
 * line). The phone then asks with StartLocationInformation, the box passes that on as a `{"kind":"location"}` record
 * ([Request]), and this feed sends the sentences it names ([Nmea]) once a second, each as one CH_INPUT
 * `INPUT_LOCATION`, until the box says stop or the session ends. The box forwards only what the phone selected, no
 * faster than it asked. A release build has no location permission, pushes no `location:` and is never asked.
 */
class CarGpsFeed(
    private val context: Context,
    /** Sends one sentence to the box; false when it was dropped. Any thread. */
    private val send: (String) -> Boolean,
) {
    /** The box's `{"kind":"location"}` record: the phone started (with the sentences to build) or stopped. */
    data class Request(
        val start: Boolean,
        val sentences: List<String>,
        val intervalMs: Map<String, Long>,
    ) {
        companion object {
            fun of(obj: JSONObject): Request? {
                if (obj.optString("kind") != "location") return null
                val start =
                    when (obj.optString("state")) {
                        "start" -> true
                        "stop" -> false
                        else -> return null
                    }
                val names = obj.optJSONArray("sentences")
                val sentences = (0 until (names?.length() ?: 0)).mapNotNull { names?.optString(it)?.takeIf(String::isNotEmpty) }
                val gaps = obj.optJSONObject("intervalMs")
                val intervals =
                    gaps
                        ?.keys()
                        ?.asSequence()
                        ?.associateWith { gaps.optLong(it) }
                        .orEmpty()
                return Request(start, sentences, intervals)
            }
        }
    }

    private val manager = context.getSystemService(LocationManager::class.java)
    private var wanted: List<String> = emptyList()
    private var listening = false
    private var fixes = 0L
    private var sent = 0L
    private var dropped = 0L

    private val listener = LocationListener { location -> onFix(location) }

    /** One `{"kind":"location"}` record from the box. Any thread, like [stop]. */
    @Synchronized
    fun onRecord(obj: JSONObject) {
        val request = Request.of(obj) ?: return
        if (!request.start) {
            stop("the phone stopped asking")
            return
        }
        wanted = request.sentences.filter { it in Nmea.BUILT }
        logInfo("[LOCATION] the phone asks for ${request.sentences} (intervals ${request.intervalMs}); building $wanted", tag = Logger.Tags.ADAPTR)
        if (wanted.isEmpty()) {
            stop("none of the sentences asked for is one Carlink builds")
            return
        }
        listen()
    }

    /** Stop listening; the next start comes from the phone again. */
    @Synchronized
    fun stop(why: String) {
        if (!listening) return
        runCatching { manager?.removeUpdates(listener) }
        listening = false
        logInfo("[LOCATION] stopped ($why) after $fixes fix(es): $sent sentence(s) sent, $dropped dropped", tag = Logger.Tags.ADAPTR)
    }

    @SuppressLint("MissingPermission") // permitted() is checked first; a revoked grant throws, caught below
    private fun listen() {
        if (listening) return
        val m = manager
        if (m == null || !permitted(context)) {
            logWarn("[LOCATION] the phone asked, but this build may not read the GPS (no location permission or no LocationManager)", tag = Logger.Tags.ADAPTR)
            return
        }
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, FIX_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            listening = true
            fixes = 0
            sent = 0
            dropped = 0
            logInfo("[LOCATION] listening to the head unit's GPS every $FIX_INTERVAL_MS ms", tag = Logger.Tags.ADAPTR)
        } catch (e: SecurityException) {
            logWarn("[LOCATION] the GPS refused this build: ${e.message}", tag = Logger.Tags.ADAPTR)
        } catch (e: IllegalArgumentException) {
            logWarn("[LOCATION] this head unit has no GPS provider: ${e.message}", tag = Logger.Tags.ADAPTR)
        }
    }

    @Synchronized
    private fun onFix(location: Location) {
        fixes++
        for (s in Nmea.sentences(fixOf(location), wanted)) {
            if (send(s)) sent++ else dropped++
        }
        if (fixes == 1L || fixes % LOG_EVERY == 0L) {
            logInfo("[LOCATION] fix $fixes (±${location.accuracy} m): $sent sent, $dropped dropped", tag = Logger.Tags.ADAPTR)
        }
    }

    companion object {
        private const val FIX_INTERVAL_MS = 1_000L
        private const val LOG_EVERY = 60L

        /** A debug build whose bench file pushes `location:`: the only build that asks for the permission. */
        fun benchWanted(context: Context): Boolean = BuildConfig.DEBUG && BenchConfigExtra.sets(context, "location")

        fun permitted(context: Context): Boolean = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun fixOf(l: Location): Nmea.Fix =
            Nmea.Fix(
                utcMs = l.time,
                latitude = l.latitude,
                longitude = l.longitude,
                ellipsoidAltitudeM = l.altitude.takeIf { l.hasAltitude() },
                mslAltitudeM = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && l.hasMslAltitude()) l.mslAltitudeMeters else null,
                speedMps = l.speed.takeIf { l.hasSpeed() },
                bearingDeg = l.bearing.takeIf { l.hasBearing() },
                accuracyM = l.accuracy.takeIf { l.hasAccuracy() },
                satellites = l.extras?.getInt("satellites", -1)?.takeIf { it >= 0 },
            )
    }
}
