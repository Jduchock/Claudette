package com.duchock.claudette

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class ClaudetteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nova listening",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while Nova is actively listening for the wake word."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "claudette_listening"
    }
}
