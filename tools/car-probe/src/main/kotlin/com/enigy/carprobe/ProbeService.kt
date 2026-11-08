package com.enigy.carprobe

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * Logs through a drive what can only be seen moving: vehicle property changes (item 3), the car's GPS fixes, satellites
 * and NMEA sentences (item 4), the driving restrictions that would gate video (item 5), the volumes (item 6) and the
 * network. Minute summaries keep the file small; the first changes of each kind are logged in full.
 */
class ProbeService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    private var lm: LocationManager? = null

    // Per property: changes this minute, and when it was last logged in full.
    private val changes = HashMap<String, Int>()
    private val lastLogged = HashMap<String, Long>()
    private var fixes = 0
    private var bestAccuracy = Float.MAX_VALUE
    private var lastFix: Location? = null
    private val nmeaCounts = HashMap<String, Int>()
    private val nmeaSamples = HashMap<String, MutableList<String>>()
    private var lastVolumes: Map<String, String> = emptyMap()
    private var satellites = ""

    private val propCallback =
        object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) = handler.post { onProperty(value) }.let { }

            override fun onErrorEvent(
                propId: Int,
                areaId: Int,
            ) {
                ProbeLog.log("vehicle", "what" to "error event", "name" to VehiclePropertyIds.toString(propId), "area" to areaId)
            }
        }

    private val locationListener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                fixes++
                if (location.hasAccuracy()) bestAccuracy = minOf(bestAccuracy, location.accuracy)
                lastFix = location
                // The first ten fixes in full, then the minute summary.
                if (fixes <= FULL_FIXES) logFix("fix", location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: android.os.Bundle?,
            ) = Unit

            override fun onProviderEnabled(provider: String) = ProbeLog.log("location", "what" to "provider on", "provider" to provider)

            override fun onProviderDisabled(provider: String) = ProbeLog.log("location", "what" to "provider off", "provider" to provider)
        }

    private val gnssCallback =
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                var cn0 = 0f
                val constellations = HashMap<Int, Int>()
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) used++
                    cn0 += status.getCn0DbHz(i)
                    constellations.merge(status.getConstellationType(i), 1, Int::plus)
                }
                val avg = if (status.satelliteCount > 0) cn0 / status.satelliteCount else 0f
                satellites = "${status.satelliteCount} seen, $used used, cn0 avg ${"%.1f".format(avg)}, constellations $constellations"
            }
        }

    private val nmeaListener =
        OnNmeaMessageListener { message, _ ->
            val type = message.substringBefore(',').trim()
            nmeaCounts.merge(type, 1, Int::plus)
            val samples = nmeaSamples.getOrPut(type) { ArrayList() }
            if (samples.size < NMEA_SAMPLES) samples += message.trim()
        }

    private val uxListener =
        CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r -> logUx("changed", r) }

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) {
                ProbeLog.log(
                    "video",
                    "what" to "network changed",
                    "internet" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    "validated" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    "wifi" to caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    "cellular" to caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                )
            }

            override fun onLost(network: Network) = ProbeLog.log("video", "what" to "network lost")
        }

    private val minute =
        object : Runnable {
            override fun run() {
                summary()
                handler.postDelayed(this, SUMMARY_MS)
            }
        }

    private val volumePoll =
        object : Runnable {
            override fun run() {
                val now = Snapshots.volumes(getSystemService(AudioManager::class.java))
                if (now != lastVolumes) {
                    if (lastVolumes.isNotEmpty()) ProbeLog.log("audio", "what" to "volume changed", "volumes" to now)
                    lastVolumes = now
                }
                handler.postDelayed(this, VOLUME_POLL_MS)
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("probe").apply { start() }
        handler = Handler(thread.looper)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Carlink Probe", NotificationManager.IMPORTANCE_LOW))
        val note = Notification.Builder(this, CHANNEL).setContentTitle("Carlink Probe is logging").setSmallIcon(android.R.drawable.ic_menu_info_details).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTE_ID, note)
        ProbeLog.open(this)
        handler.post { begin() }
    }

    private fun begin() {
        ProbeLog.log("meta", "what" to "logging started")
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            car = runCatching { Car.createCar(this) }.onFailure { ProbeLog.log("meta", "what" to "no car", "error" to it) }.getOrNull()
        }
        Snapshots.all(this, car)
        car?.let { watchVehicle(it) }
        watchLocation()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback, handler)
        handler.post(volumePoll)
        handler.postDelayed(minute, SUMMARY_MS)
    }

    private fun watchVehicle(c: Car) {
        props = runCatching { c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }.getOrNull()
        val readable = props?.propertyList.orEmpty().filter { it.access != CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_WRITE }
        var watched = 0
        for (cfg in readable) {
            if (cfg.changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_STATIC) continue
            val rate = if (cfg.changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) minOf(cfg.maxSampleRate, 1f) else 0f
            if (runCatching { props?.registerCallback(propCallback, cfg.propertyId, rate) == true }.getOrDefault(false)) watched++
        }
        ProbeLog.log("vehicle", "what" to "watching", "count" to watched, "of" to readable.size)
        ux = runCatching { c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager }.getOrNull()
        ux?.let {
            runCatching { it.registerListener(uxListener) }.onFailure { e -> ProbeLog.log("video", "what" to "ux listener", "error" to e) }
            runCatching { it.currentCarUxRestrictions }.getOrNull()?.let { r -> logUx("now", r) }
        }
    }

    private fun onProperty(v: CarPropertyValue<*>) {
        val name = VehiclePropertyIds.toString(v.propertyId)
        val key = "$name/${v.areaId}"
        changes.merge(key, 1, Int::plus)
        val now = System.currentTimeMillis()
        // Each property's first change in full, then at most one a minute; the summary counts the rest.
        if (now - (lastLogged[key] ?: 0L) >= SUMMARY_MS) {
            lastLogged[key] = now
            val value =
                when (val x = v.value) {
                    is Array<*> -> x.toList()
                    is IntArray -> x.toList()
                    is FloatArray -> x.toList()
                    else -> x
                }
            ProbeLog.log("vehicle", "what" to "change", "name" to name, "area" to v.areaId, "value" to value, "status" to v.status)
        }
    }

    @SuppressLint("MissingPermission") // checked just before
    private fun watchLocation() {
        val l = getSystemService(LocationManager::class.java)
        lm = l
        val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ProbeLog.log(
            "location",
            "what" to "providers",
            "all" to l.allProviders,
            "enabled" to l.getProviders(true),
            "fineGranted" to fine,
            "gnssModel" to runCatching { l.gnssHardwareModelName }.getOrNull(),
            "gnssYear" to runCatching { l.gnssYearOfHardware }.getOrNull(),
            "gnssCapabilities" to runCatching { l.gnssCapabilities.toString() }.getOrNull(),
        )
        if (!fine) return
        for (p in l.getProviders(true)) {
            runCatching { l.getLastKnownLocation(p) }.getOrNull()?.let { logFix("last known $p", it) }
            runCatching { l.requestLocationUpdates(p, FIX_MS, 0f, locationListener, thread.looper) }
                .onFailure { ProbeLog.log("location", "what" to "updates refused", "provider" to p, "error" to it) }
        }
        runCatching { l.registerGnssStatusCallback(gnssCallback, handler) }.onFailure { ProbeLog.log("location", "what" to "gnss status refused", "error" to it) }
        runCatching { l.addNmeaListener(nmeaListener, handler) }.onFailure { ProbeLog.log("location", "what" to "nmea refused", "error" to it) }
    }

    private fun logFix(
        what: String,
        f: Location,
    ) {
        ProbeLog.log(
            "location",
            "what" to what,
            "provider" to f.provider,
            "lat" to "%.5f".format(f.latitude),
            "lon" to "%.5f".format(f.longitude),
            "accuracyM" to (if (f.hasAccuracy()) f.accuracy else null),
            "speedMps" to (if (f.hasSpeed()) f.speed else null),
            "bearing" to (if (f.hasBearing()) f.bearing else null),
            "altitudeM" to (if (f.hasAltitude()) f.altitude else null),
            "ageMs" to (System.currentTimeMillis() - f.time),
        )
    }

    private fun logUx(
        what: String,
        r: CarUxRestrictions,
    ) {
        ProbeLog.log(
            "video",
            "what" to "ux restrictions $what",
            "requiresDistractionOptimization" to r.isRequiresDistractionOptimization,
            "active" to "0x" + Integer.toHexString(r.activeRestrictions),
            "noVideo" to (r.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO != 0),
        )
    }

    private fun summary() {
        ProbeLog.log(
            "summary",
            "propertyChanges" to changes.toMap(),
            "fixes" to fixes,
            "bestAccuracyM" to (if (bestAccuracy == Float.MAX_VALUE) null else bestAccuracy),
            "lastFixAgeMs" to lastFix?.let { System.currentTimeMillis() - it.time },
            "lastSpeedMps" to lastFix?.takeIf { it.hasSpeed() }?.speed,
            "satellites" to satellites,
            "nmea" to nmeaCounts.toMap(),
        )
        if (nmeaSamples.isNotEmpty()) {
            ProbeLog.log("location", "what" to "nmea samples", "samples" to nmeaSamples.mapValues { it.value.toList() })
            nmeaSamples.clear()
        }
        changes.clear()
        nmeaCounts.clear()
        fixes = 0
        bestAccuracy = Float.MAX_VALUE
    }

    override fun onDestroy() {
        handler.post {
            summary()
            ProbeLog.log("meta", "what" to "logging stopped")
            runCatching { props?.unregisterCallback(propCallback) }
            runCatching { ux?.unregisterListener() }
            runCatching { lm?.removeUpdates(locationListener) }
            runCatching { lm?.unregisterGnssStatusCallback(gnssCallback) }
            runCatching { lm?.removeNmeaListener(nmeaListener) }
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
            runCatching { car?.disconnect() }
            thread.quitSafely()
        }
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "probe"
        const val NOTE_ID = 1
        const val SUMMARY_MS = 60_000L
        const val VOLUME_POLL_MS = 2_000L
        const val FIX_MS = 1_000L
        const val FULL_FIXES = 10
        const val NMEA_SAMPLES = 3
    }
}
