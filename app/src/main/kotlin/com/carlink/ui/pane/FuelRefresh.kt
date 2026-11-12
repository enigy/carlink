package com.carlink.ui.pane

import android.os.SystemClock
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carlink.fuel.FuelStations
import com.carlink.fuel.RefreshGate
import kotlinx.coroutines.delay

/**
 * The Fuel card's refresh, by its source line, parked and moving (owner, 12 Nov): one tap looks the stations up again
 * now ([FuelStations.refresh]). A spinner while a lookup runs; greyed for a minute after any lookup and while a busy
 * service's own retry waits ([RefreshGate]), so taps neither stack nor spend the driver's Google lookups. The car's own
 * readings are live already: this is for the stations and their prices. 80 dp, like the stars, and above the rows, so
 * a row's one tap stays directions.
 */
@Composable
internal fun RefreshButton(
    view: FuelStations.View,
    onRefresh: () -> Unit,
) {
    // The clock the rest is told by, ticking only until the minute is up.
    val now by produceState(SystemClock.elapsedRealtime(), view.restUntil) {
        while (value < view.restUntil) {
            delay(view.restUntil - value)
            value = SystemClock.elapsedRealtime()
        }
    }
    val looking = view.status == FuelStations.Status.LOOKING
    val enabled = RefreshGate.allows(now, view.restUntil, looking, waiting = view.status == FuelStations.Status.BUSY)
    IconButton(onClick = onRefresh, enabled = enabled, modifier = Modifier.size(80.dp)) {
        if (looking) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 4.dp, color = LocalContentColor.current)
        } else {
            Icon(Icons.Default.Refresh, contentDescription = "Look up the stations again", modifier = Modifier.size(45.dp))
        }
    }
}
