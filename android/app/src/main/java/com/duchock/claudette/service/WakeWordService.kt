package com.duchock.claudette.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Base64
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
import com.duchock.claudette.bible.BibleBookmark
import com.duchock.claudette.bible.BibleControl
import com.duchock.claudette.bible.BibleNotes
import com.duchock.claudette.bible.BibleRepo
import com.duchock.claudette.bible.BibleTools
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
import java.io.File
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
    @Volatile private var currentConversation: ConversationManager? = null
    @Volatile private var reading = false
    private var readingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        detector = OpenWakeWordDetector(applicationContext)
        val loaded = detector.initialize()
        DebugStatus.wakeModelLoaded = loaded
        DebugStatus.event(if (loaded) "Wake model loaded" else "Wake model MISSING")
        stt = AndroidSpeechToText(applicationContext)
        tts = TtsPlayer(applicationContext)
        // Load Nova's persistent memory (encrypted) so she remembers John from the first turn.
        com.duchock.claudette.memory.MemoryStore.ensureLoaded(applicationContext)
        // On-demand location + nearby-places (D13); init with the shared HTTP client.
        com.duchock.claudette.location.LocationProvider.init(applicationContext)
        com.duchock.claudette.net.PlacesRepo.init(applicationContext, http)
        // Bible companion (D23): init note store + tools now; load the KJV off the main thread.
        BibleNotes.init(applicationContext)
        BibleTools.init(applicationContext)
        // Warm the demo inventory dataset and the King James Version off the main thread.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            com.duchock.claudette.demo.InventoryRepo.ensureLoaded(applicationContext)
            BibleRepo.ensureLoaded(applicationContext)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopListening(); stopSelf(); return START_NOT_STICKY }
            ACTION_TEST_WAKE -> onWakeDetected()
            ACTION_ANALYZE_IMAGE -> analyzeImage(intent.getStringExtra(EXTRA_IMAGE_PATH))
            ACTION_START_READING -> startReading(resolveRef(intent.getStringExtra(EXTRA_REF)))
            ACTION_STOP_READING -> stopReading()
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
        if (reading) stopReading()
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
        val conversation = currentConversation ?: ConversationManager(claude).also { currentConversation = it }

        updateNotification(CONVERSING_TEXT)
        conversationJob = lifecycleScope.launch {
            var startReadingAt: BibleRepo.Ref? = null
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
                    val readAt = BibleControl.readingStart(utterance, this@WakeWordService)
                    if (readAt != null) { startReadingAt = readAt; break }
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
                val convo = conversation
                endConversation()
                // Distill this conversation into long-term memory in the background (no-op in demo mode).
                lifecycleScope.launch { runCatching { convo.reflect() } }
            }
            startReadingAt?.let { startReading(it) }
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

    /** John fed Nova a photo from the UI: analyze it, fold it into the conversation, speak her read. */
    private fun analyzeImage(path: String?) {
        if (path.isNullOrBlank()) return
        val anthropicKey = Secrets.anthropicKey(this)
        val elevenKey = Secrets.elevenLabsKey(this)
        val voiceId = Secrets.voiceId(this)
        if (anthropicKey.isBlank()) { DebugStatus.event("Missing Anthropic key — check Settings"); return }
        val convo = currentConversation
            ?: ConversationManager(ClaudeClient(anthropicKey, http)).also { currentConversation = it }
        val eleven = if (elevenKey.isNotBlank()) ElevenLabsClient(elevenKey, http) else null
        lifecycleScope.launch {
            try {
                val bytes = File(path).readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                DebugStatus.event("Looking at your photo…")
                val reply = convo.handleImage(b64, "image/jpeg")
                if (reply.isNullOrBlank()) { DebugStatus.event("Couldn't read that image"); return@launch }
                DebugStatus.event("Nova: ${reply.take(80)}")
                if (eleven != null && voiceId.isNotBlank()) {
                    eleven.synthesize(reply, voiceId)?.let { tts.play(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "analyzeImage failed", e); DebugStatus.event("Image error: ${e.message}")
            }
        }
    }

    /** Read Scripture aloud from [start], saving the bookmark as it goes (D23). Capture stays on so "Nova" can interrupt. */
    private fun startReading(start: BibleRepo.Ref) {
        val elevenKey = Secrets.elevenLabsKey(this)
        val voiceId = Secrets.voiceId(this)
        if (elevenKey.isBlank() || voiceId.isBlank()) { DebugStatus.event("Missing ElevenLabs key/voice"); return }
        if (!BibleRepo.isLoaded()) { DebugStatus.event("Scripture still loading — try again in a moment"); return }
        readingJob?.cancel()
        reading = true
        BibleTools.readingNow = true
        val eleven = ElevenLabsClient(elevenKey, http)
        updateNotification("Reading — ${BibleRepo.label(start)}")
        readingJob = lifecycleScope.launch {
            try {
                var ref: BibleRepo.Ref? = start
                while (reading && ref != null) {
                    BibleBookmark.set(this@WakeWordService, ref)
                    val text = BibleRepo.verse(ref) ?: break
                    val spoken = if (ref.verse == 1)
                        "${BibleRepo.bookName(ref.book)}, chapter ${ref.chapter}. $text" else text
                    DebugStatus.event("Reading ${BibleRepo.label(ref)}")
                    val audio = eleven.synthesize(spoken, voiceId) ?: break
                    tts.play(audio)
                    ref = BibleRepo.next(ref)
                }
                if (reading && ref == null) {
                    eleven.synthesize("And that is the end of the Scriptures. Amen.", voiceId)?.let { tts.play(it) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // stopped by John; the bookmark is already saved at the current verse
            } catch (e: Exception) {
                Log.e(TAG, "reading error", e)
            } finally {
                reading = false
                BibleTools.readingNow = false
                if (Prefs.isListeningEnabled(this@WakeWordService)) updateNotification(LISTENING_TEXT)
            }
        }
    }

    private fun resolveRef(s: String?): BibleRepo.Ref =
        if (s.isNullOrBlank() || s == "continue") BibleBookmark.get(this)
        else BibleRepo.parse(s) ?: BibleBookmark.get(this)

    /** Halt reading (STOP button or wake). Bookmark is already saved at the current verse. */
    private fun stopReading() {
        if (!reading && readingJob == null) return
        reading = false
        BibleTools.readingNow = false
        readingJob?.cancel()
        readingJob = null
        DebugStatus.event("Stopped reading")
        if (Prefs.isListeningEnabled(this)) updateNotification(LISTENING_TEXT)
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
        const val ACTION_ANALYZE_IMAGE = "com.duchock.claudette.ANALYZE_IMAGE"
        const val EXTRA_IMAGE_PATH = "image_path"
        const val ACTION_START_READING = "com.duchock.claudette.START_READING"
        const val ACTION_STOP_READING = "com.duchock.claudette.STOP_READING"
        const val EXTRA_REF = "bible_ref"
    }
}
