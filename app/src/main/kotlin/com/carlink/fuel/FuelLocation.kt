package com.carlink.fuel

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.carlink.car.CarGpsFeed
import com.carlink.logging.Logger
import com.carlink.logging.logWarn

/**
 * The head unit's position for the Fuel card's stations, only while the card is up with Nearby stations on: the last
 * fix at once, then GPS updates every half minute or 200 m. Needs the location permission Car GPS uses
 * ([CarGpsFeed.permitted]); without it, nothing. Never logged. Main thread.
 */
@SuppressLint("MissingPermission") // start() checks the permission first
internal object FuelLocation {
    private const val UPDATE_MS = 30_000L
    private const val UPDATE_M = 200f
    private val PROVIDERS = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    private var manager: LocationManager? = null
    private var listener: LocationListener? = null

    fun start(
        context: Context,
        onFix: (GeoPoint) -> Unit,
    ) {
        if (listener != null || !CarGpsFeed.permitted(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val l = LocationListener { onFix(it.point()) }
        runCatching {
            PROVIDERS.firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }?.let { onFix(it.point()) }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_MS, UPDATE_M, l, Looper.getMainLooper())
            manager = lm
            listener = l
        }.onFailure { logWarn("[FUEL] no position updates: ${it.javaClass.simpleName}", tag = Logger.Tags.ADAPTR) }
    }

    fun stop() {
        val l = listener ?: return
        runCatching { manager?.removeUpdates(l) }
        listener = null
        manager = null
    }

    private fun Location.point() = GeoPoint(latitude, longitude)
}
