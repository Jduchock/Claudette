package com.duchock.claudette.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.duchock.claudette.ClaudetteApp
import com.duchock.claudette.MainActivity
import com.duchock.claudette.R
import com.duchock.claudette.audio.AudioCapture
import com.duchock.claudette.audio.OpenWakeWordDetector
import com.duchock.claudette.audio.WakeWordDetector

/**
 * Always-on foreground service that owns the audio pipeline and the wake-word loop.
 * Phase 1: capture audio -> detector -> on wake, log it. Phase 2 wires the wake event
 * to STT -> Claude -> ElevenLabs TTS.
 */
class WakeWordService : LifecycleService() {

    private lateinit var detector: WakeWordDetector
    private var capture: AudioCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        detector = OpenWakeWordDetector(applicationContext)
        detector.initialize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST_WAKE -> onWakeDetected()
            else -> startListening()
        }
        return START_STICKY
    }

    private fun startListening() {
        startForegroundWithNotification()
        acquireWakeLock()
        if (capture != null) return
        capture = AudioCapture(detector.frameSize) { frame ->
            if (detector.process(frame).detected) onWakeDetected()
        }.also { it.start() }
        Log.i(TAG, "Listening started")
    }

    private fun stopListening() {
        capture?.stop()
        capture = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Listening stopped")
    }

    /** Phase 1 stub for the wake event. */
    private fun onWakeDetected() {
        Log.i(TAG, "Wake word 'Claudette' detected")
        // TODO(Phase 2): earcon -> SpeechRecognizer -> Claude -> ElevenLabs TTS.
    }

    private fun startForegroundWithNotification() {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, ClaudetteApp.CHANNEL_ID)
            .setContentTitle("Claudette is listening")
            .setContentText("Say \"Claudette\" to get her attention.")
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stopIntent)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claudette:listen").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopListening()
        detector.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.duchock.claudette.STOP"
        const val ACTION_TEST_WAKE = "com.duchock.claudette.TEST_WAKE"
    }
}
