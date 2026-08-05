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
import kotlin.coroutines.resume

/**
 * Wraps Android's on-device SpeechRecognizer as a one-shot suspend call.
 * NOTE: SpeechRecognizer owns the mic while active, so the wake-word AudioCapture
 * must be paused for the duration of a conversation (handled by WakeWordService).
 * Privacy: Android's recognizer may route audio to Google (threat S3).
 */
class AndroidSpeechToText(private val context: Context) : SpeechToText {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recognizer: SpeechRecognizer? = null

    override suspend fun listenOnce(): String? = suspendCancellableCoroutine { cont ->
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "Speech recognition not available on this device")
                if (cont.isActive) cont.resume(null)
                return@post
            }
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    finish(text)
                }
                override fun onError(error: Int) { finish(null) }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                private fun finish(text: String?) {
                    runCatching { sr.destroy() }
                    if (recognizer === sr) recognizer = null
                    if (cont.isActive) cont.resume(text)
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            runCatching { sr.startListening(intent) }
        }
        cont.invokeOnCancellation { cancel() }
    }

    override fun cancel() {
        main.post {
            recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
            recognizer = null
        }
    }

    companion object { private const val TAG = "AndroidStt" }
}
