package com.duchock.claudette.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.duchock.claudette.util.Prefs

/** Restarts the listening service after reboot, but only if the user had it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            Prefs.isListeningEnabled(context)
        ) {
            ContextCompat.startForegroundService(
                context, Intent(context, WakeWordService::class.java)
            )
        }
    }
}
