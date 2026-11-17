package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.carlink.ledger.ConnectLedger
import com.carlink.ledger.ConnectLedgerStore
import com.carlink.ledger.ConnectStats

/**
 * Settings › Adapter and about › Connect time (the connect ledger, M2): how long the CarPlay picture took to appear after
 * the display came on, over the last [ConnectStats.WINDOW] wakes that showed one with Carlink's screen in front from the
 * start ([ConnectStats.leftOutOfFigure]), read from the ledger the head unit keeps ([ConnectLedgerStore]). Read-only; it
 * follows the ledger as each connect is added.
 */
@Composable
internal fun ConnectTimeRow() {
    val context = LocalContext.current
    val store = remember { ConnectLedgerStore.get(context) }
    val ledger by store.ledger.collectAsState()
    SettingRow(title = "Connect time", subtitle = ConnectTimeText.subtitle(ledger), icon = Icons.Default.Timer)
}

/** The row's words: plain functions, for its test. */
internal object ConnectTimeText {
    /** [ledger] null: the file is still being read. */
    fun subtitle(ledger: ConnectLedger?): String {
        if (ledger == null) return "Reading…"
        val times = ConnectStats.wakePictures(ledger)
        val s =
            ConnectStats.summary(times)
                ?: return "Not enough connects yet (${times.size} of ${ConnectStats.MIN_COUNT}). Each is timed from the display coming on to the CarPlay picture."
        return "From the display coming on to the CarPlay picture, over the last ${s.count} connects: half within " +
            "${ConnectStats.seconds(s.medianMs)}, 9 in 10 within ${ConnectStats.seconds(s.ninetiethMs)}."
    }
}
