package com.duchock.claudette.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Plays MP3 bytes (from ElevenLabs) to completion. Temp file is deleted afterwards. */
class TtsPlayer(private val context: Context) {

    suspend fun play(mp3: ByteArray) = withContext(Dispatchers.IO) {
        val file = File.createTempFile("claudette_tts", ".mp3", context.cacheDir)
        file.writeBytes(mp3)
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                fun done() {
                    runCatching { mp.release() }
                    if (cont.isActive) cont.resume(Unit)
                }
                mp.setOnCompletionListener { done() }
                mp.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error $what/$extra"); done(); true
                }
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.prepare()
                    mp.start()
                } catch (e: Exception) {
                    Log.e(TAG, "TTS playback failed", e); done()
                }
                cont.invokeOnCancellation { runCatching { mp.release() } }
            }
        } finally {
            runCatching { file.delete() }
        }
    }

    companion object { private const val TAG = "TtsPlayer" }
}
