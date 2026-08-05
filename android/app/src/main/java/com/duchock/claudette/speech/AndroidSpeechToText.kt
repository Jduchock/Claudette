package com.duchock.claudette.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Wraps Android's on-device SpeechRecognizer as a one-shot suspend call.
 *
 * Demo hardening (why this is defensive):
 *  - EVERY listen has a hard watchdog timeout, so a wedged or silent recognizer can never hang
 *    the conversation loop forever (the old code could suspend indefinitely and Nova went deaf).
 *  - Any stale recognizer is torn down before a new listen starts.
 *  - A transient ERROR_RECOGNIZER_BUSY (common when the mic is grabbed turn-to-turn) is retried
 *    once after a short delay instead of failing the turn.
 *  - Callback and watchdog share a single-resume guard so the coroutine resumes exactly once.
 *
 * SpeechRecognizer owns the mic while active, so the wake-word AudioCapture must be paused for
 * the duration of a conversation (handled by WakeWordService). Privacy: Android's recognizer
 * may route audio to Google (threat S3).
 */
class AndroidSpeechToText(private val context: Context) : SpeechToText {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile private var activeResume: ((String?) -> Unit)? = null

    override suspend fun listenOnce(): String? = suspendCancellableCoroutine { cont ->
        val resumed = AtomicBoolean(false)

        val watchdog = Runnable {
            if (resumed.compareAndSet(false, true)) {
                Log.w(TAG, "listen watchdog fired -- no result in ${TIMEOUT_MS}ms; resetting recognizer")
                teardown()
                activeResume = null
                if (cont.isActive) cont.resume(null)
            }
        }
        fun resumeOnce(text: String?) {
            if (resumed.compareAndSet(false, true)) {
                activeResume = null
                main.removeCallbacks(watchdog)
                if (cont.isActive) cont.resume(text)
            }
        }
        activeResume = { resumeOnce(it) }

        main.post {
            if (resumed.get()) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "Speech recognition not available on this device")
                resumeOnce(null); return@post
            }
            startListen(retryOnBusy = true) { resumeOnce(it) }
            main.postDelayed(watchdog, TIMEOUT_MS)
        }

        cont.invokeOnCancellation {
            resumed.set(true)
            activeResume = null
            main.removeCallbacks(watchdog)
            cancel()
        }
    }

    /** Create a fresh recognizer and start listening; retry once on ERROR_RECOGNIZER_BUSY. */
    private fun startListen(retryOnBusy: Boolean, onResult: (String?) -> Unit) {
        teardown() // never let a stale recognizer hold the mic
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                teardown(); onResult(text)
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && retryOnBusy) {
                    Log.w(TAG, "recognizer busy -- retrying once")
                    teardown()
                    main.postDelayed({ startListen(retryOnBusy = false, onResult = onResult) }, 300)
                } else {
                    teardown(); onResult(null)
                }
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { sr.startListening(intent) }.onFailure {
            Log.e(TAG, "startListening threw", it); teardown(); onResult(null)
        }
    }

    /** Destroy the current recognizer (main thread). Safe to call repeatedly. */
    private fun teardown() {
        val sr = recognizer ?: return
        recognizer = null
        runCatching { sr.cancel() }
        runCatching { sr.destroy() }
    }

    override fun cancel() {
        main.post { teardown() }
    }

    /** End an in-flight listen right now, resuming it with null (used when a photo interrupts a turn). */
    override fun interrupt() {
        val r = activeResume ?: return
        main.post { teardown(); r(null) }
    }

    companion object {
        private const val TAG = "AndroidStt"
        private const val TIMEOUT_MS = 12_000L
    }
}
