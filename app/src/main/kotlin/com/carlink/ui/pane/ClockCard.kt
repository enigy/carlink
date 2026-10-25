package com.carlink.ui.pane

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The idle card: the time, in the head unit's 12- or 24-hour style, and the date. Ticks on the second. */
@Composable
internal fun ClockCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timeFormat = remember(context) { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm") }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(MILLIS_PER_SECOND - System.currentTimeMillis() % MILLIS_PER_SECOND)
        }
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(now.format(timeFormat), style = MaterialTheme.typography.displayLarge.copy(fontSize = 112.sp, lineHeight = 120.sp))
        Text(now.format(DATE_FORMAT), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
internal const val MILLIS_PER_SECOND = 1_000L
