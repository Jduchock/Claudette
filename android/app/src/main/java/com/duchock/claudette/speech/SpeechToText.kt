package com.duchock.claudette.speech

/** Swappable speech-to-text seam (Android recognizer now; Deepgram/Vosk later). */
interface SpeechToText {
    /** Suspends until one utterance is transcribed; returns null on no-speech/error. */
    suspend fun listenOnce(): String?
    fun cancel()
}
