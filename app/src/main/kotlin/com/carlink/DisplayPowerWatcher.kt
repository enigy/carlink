package com.carlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.carlink.logging.logInfo

/**
 * The head unit's display power, ported from Carlink2 (bdca833, [173] 523dc45): a DisplayManager listener on the
 * default display (STATE_ON or not), the more reliable signal, and the ACTION_SCREEN_OFF/ON broadcast beside it, as
 * Carlink2 kept both. Both report every change, so the receivers of [onOff] and [onOn] must be idempotent
 * ([CarlinkManager.onDisplayOff] / [CarlinkManager.onDisplayOn] are). Callbacks arrive on the main thread.
 */
class DisplayPowerWatcher(
    private val context: Context,
    private val onOff: (source: String) -> Unit,
    private val onOn: (source: String) -> Unit,
) {
    private var lastOn = true

    private val listener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val state = displayManager()?.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: return
                val on = state == Display.STATE_ON
                if (on == lastOn) return // brightness, mode and the like: not an on/off change
                lastOn = on
                if (on) onOn("display listener, state $state") else onOff("display listener, state $state")
            }
        }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onOff("screen-off broadcast")
                    Intent.ACTION_SCREEN_ON -> onOn("screen-on broadcast")
                }
            }
        }

    private fun displayManager(): DisplayManager? = context.getSystemService(DisplayManager::class.java)

    fun register() {
        val dm = displayManager()
        lastOn = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.state?.let { it == Display.STATE_ON } ?: true
        dm?.registerDisplayListener(listener, null) // null handler: the main thread
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        logInfo("[POWER] watching the head unit's display (on=$lastOn)", tag = "MAIN")
    }

    fun unregister() {
        runCatching { displayManager()?.unregisterDisplayListener(listener) }
        runCatching { context.unregisterReceiver(receiver) }
    }
}
