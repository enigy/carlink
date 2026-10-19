package com.carlink.ui.components

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.DisplayModeStore

/**
 * Gives a dialog's window the app's display mode (Carlink2 [230]).
 *
 * Compose's Dialog and AlertDialog open in a window of their own, which starts with the system bars
 * showing. Since Android 11 the focused window decides whether the bars show, so while a dialog had
 * focus the GM head unit put its notification and navigation bars back — what MainActivity's
 * `applyDisplayModeTo` already fixes for CarAppActivity's window. Call this inside the dialog's
 * content (any slot of an AlertDialog); it draws nothing.
 *
 * [mode] defaults to the saved display mode; the Display Mode dialog passes its live preview.
 */
@Composable
fun DialogSystemBars(mode: DisplayMode? = null) {
    val view = LocalView.current
    val context = LocalContext.current
    val target = mode ?: remember { DisplayModeStore(context).get() }
    DisposableEffect(view, target) {
        (view.parent as? DialogWindowProvider)?.window?.let { target.applySystemBars(it) }
        onDispose {}
    }
}

/** Show the bars this mode keeps and hide the ones it drops on [window] — the calls MainActivity makes. */
fun DisplayMode.applySystemBars(window: Window) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (visibleBarTypes != 0) controller.show(visibleBarTypes)
    if (hiddenBarTypes != 0) controller.hide(hiddenBarTypes)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}
