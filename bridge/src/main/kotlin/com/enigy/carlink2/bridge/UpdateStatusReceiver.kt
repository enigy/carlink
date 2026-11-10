package com.enigy.carlink2.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * API 7: the install session's status for the bridge's own update, handed to [BridgeUpdater]. Not exported: only the
 * system's PackageInstaller reaches it, through the PendingIntent the update commits with.
 */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val updater = BridgeUpdater.active
        if (updater == null) {
            Log.w("Carlink2Bridge", "[BRIDGE_UPDATE] an install status with no update running")
            return
        }
        updater.onStatus(intent)
    }

    companion object {
        const val ACTION = "com.enigy.carlink2.bridge.UPDATE_STATUS"

        /** The system's confirm screen a STATUS_PENDING_USER_ACTION carries, or null. */
        fun confirmOf(status: Intent): Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                status.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                status.getParcelableExtra(Intent.EXTRA_INTENT)
            }
    }
}
