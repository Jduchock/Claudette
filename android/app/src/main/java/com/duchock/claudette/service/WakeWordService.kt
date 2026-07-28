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
import androidx.lifecycle.lifecycleScope
import com.duchock.claudette.ClaudetteApp
import com.duchock.claudette.MainActivity
import com.duchock.claudette.R
import com.duchock.claudette.audio.AudioCapture
import com.duchock.claudette.audio.OpenWakeWordDetector
import com.duchock.claudette.audio.WakeWordDetector
import com.duchock.claudette.conversation.ConversationManager
import com.duchock.claudette.conversation.Dismiss
import com.duchock.claudette.net.ClaudeClient
import com.duchock.claudette.net.ElevenLabsClient
import com.duchock.claudette.speech.AndroidSpeechToText
import com.duchock.claudette.speech.SpeechToText
import com.duchock.claudette.speech.TtsPlayer
import com.duchock.claudette.util.DebugStatus
import com.duchock.claudette.util.Prefs
import com.duchock.claudette.util.Secrets
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Always-on foreground service. Idle -> listens for the wake word. On wake, it releases the
 * mic from wake-word capture, opens a conversation (STT -> Claude -> ElevenLabs TTS) that
 * stays open turn-to-turn until the user dismisses it (D10), then returns to wake-word listening.
 */
class WakeWordService : LifecycleService() {

    private lateinit var detector: WakeWordDetector
    private var capture: AudioCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val http by lazy {
        OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    private lateinit var stt: SpeechToText
    private lateinit var tts: TtsPlayer

    private val inConversation = AtomicBoolean(false)
    private var conversationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        detector = OpenWakeWordDetector(applicationContext)
        val loaded = detector.initialize()
        DebugStatus.wakeModelLoaded = loaded
        DebugStatus.event(if (loaded) "Wake model loaded" else "Wake model MISSING")
        stt = AndroidSpeechToText(applicationContext)
        tts = TtsPlayer(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopListening(); stopSelf(); return START_NOT_STICKY }
            ACTION_TEST_WAKE -> onWakeDetected()
            else -> startListening()
        }
        return START_STICKY
    }

    private fun startListening() {
        startForegroundWithNotification(LISTENING_TEXT)
        acquireWakeLock()
        startCapture()
        DebugStatus.listening = true
        DebugStatus.event("Listening for \"Nova\"")
        Log.i(TAG, "Listening started")
    }

    private fun startCapture() {
        if (capture != null || inConversation.get()) return
        capture = AudioCapture(detector.frameSize) { frame ->
            val result = detector.process(frame)
            DebugStatus.lastWakeScore = result.score
            if (result.detected) onWakeDetected()
        }.also { it.start() }
    }

    private fun stopCapture() { capture?.stop(); capture = null }

    private fun stopListening() {
        conversationJob?.cancel()
        stt.cancel()
        stopCapture()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        DebugStatus.listening = false
        DebugStatus.event("Stopped")
        Log.i(TAG, "Listening stopped")
    }

    /** Wake fired -> run the conversation loop. */
    private fun onWakeDetected() {
        if (!inConversation.compareAndSet(false, true)) return
        Log.i(TAG, "Wake word 'Nova' detected")
        DebugStatus.event("Wake detected (%.2f)".format(DebugStatus.lastWakeScore))
        stopCapture() // free the mic for SpeechRecognizer

        val anthropicKey = Secrets.anthropicKey(this)
        val elevenKey = Secrets.elevenLabsKey(this)
        val voiceId = Secrets.voiceId(this)

        if (anthropicKey.isBlank() || elevenKey.isBlank() || voiceId.isBlank()) {
            Log.w(TAG, "Missing keys/voice -- set them in local.properties (or Settings). Aborting turn.")
            DebugStatus.event("Missing keys/voice — check local.properties")
            endConversation()
            return
        }

        val claude = ClaudeClient(anthropicKey, http)
        val eleven = ElevenLabsClient(elevenKey, http)
        val conversation = ConversationManager(claude)

        updateNotification(CONVERSING_TEXT)
        conversationJob = lifecycleScope.launch {
            try {
                var emptyStreak = 0
                while (inConversation.get()) {
                    DebugStatus.event("Listening for your request…")
                    val utterance = stt.listenOnce()
                    if (utterance.isNullOrBlank()) {
                        // two silent listens in a row -> assume the user walked away
                        DebugStatus.event("Heard nothing")
                        if (++emptyStreak >= 2) break else continue
                    }
                    emptyStreak = 0
                    DebugStatus.event("Heard: \"$utterance\"")
                    if (Dismiss.isDismiss(utterance)) {
                        eleven.synthesize("Okay. I'm here when you need me.", voiceId)?.let { tts.play(it) }
                        break
                    }
                    DebugStatus.event("Thinking…")
                    val reply = conversation.handle(utterance)
                    if (reply == null) {
                        DebugStatus.event("Claude call failed (see ClaudeClient log)")
                        eleven.synthesize("Sorry, I hit a snag reaching my brain. Try me again.", voiceId)
                            ?.let { tts.play(it) }
                        continue
                    }
                    DebugStatus.event("Speaking: \"${reply.take(60)}\"")
                    val audio = eleven.synthesize(reply, voiceId)
                    if (audio != null) tts.play(audio)
                    else { Log.w(TAG, "TTS returned no audio"); DebugStatus.event("ElevenLabs returned no audio (voice ID?)") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Conversation loop error", e)
                DebugStatus.event("Error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                endConversation()
            }
        }
    }

    private fun endConversation() {
        inConversation.set(false)
        stt.cancel()
        if (Prefs.isListeningEnabled(this)) {
            updateNotification(LISTENING_TEXT)
            startCapture()
        }
    }

    // ---- notification ----
    private fun startForegroundWithNotification(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(android.app.NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ClaudetteApp.CHANNEL_ID)
            .setContentTitle("Nova")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claudette:listen").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        conversationJob?.cancel()
        stopListening()
        detector.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIF_ID = 1001
        private const val LISTENING_TEXT = "Listening -- say \"Nova\""
        private const val CONVERSING_TEXT = "Listening to you..."
        const val ACTION_STOP = "com.duchock.claudette.STOP"
        const val ACTION_TEST_WAKE = "com.duchock.claudette.TEST_WAKE"
    }
}
