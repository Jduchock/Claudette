package com.duchock.claudette.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Captures 16 kHz / mono / 16-bit PCM audio and delivers it in fixed-size frames on a
 * background thread. Nothing is written to disk (ref threat S2).
 *
 * start() reports success so the service can retry if the mic wasn't free yet (e.g. a
 * SpeechRecognizer from the last turn is still releasing it). stop() is safe to call from the
 * capture thread itself (the wake callback runs there), so it never joins itself.
 */
class AudioCapture(
    private val frameSize: Int,
    private val onFrame: (ShortArray) -> Unit
) {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is verified before start()
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, frameSize * 2 * 4)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord construction failed", e); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (mic busy?)")
            runCatching { rec.release() }
            return false
        }
        recorder = rec
        running = true
        try {
            rec.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            running = false; runCatching { rec.release() }; recorder = null
            return false
        }
        worker = thread(name = "claudette-audio") {
            val buf = ShortArray(frameSize)
            var dbgFrames = 0
            var dbgPeak = 0
            while (running) {
                var read = 0
                while (read < frameSize && running) {
                    val n = try { rec.read(buf, read, frameSize - read) } catch (e: Exception) { -1 }
                    if (n <= 0) break
                    read += n
                }
                if (read == frameSize) {
                    for (i in 0 until frameSize) {
                        val amp = kotlin.math.abs(buf[i].toInt())
                        if (amp > dbgPeak) dbgPeak = amp
                    }
                    if (++dbgFrames >= 25) {
                        Log.i(TAG, "DBG mic peak amplitude=$dbgPeak (max 32767)")
                        dbgFrames = 0; dbgPeak = 0
                    }
                    onFrame(buf.copyOf())
                }
            }
        }
        return true
    }

    fun stop() {
        running = false
        val w = worker
        worker = null
        // The wake callback calls stop() from the capture thread itself -- never join self.
        if (w != null && w !== Thread.currentThread()) {
            runCatching { w.join(500) }
        }
        recorder?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        recorder = null
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16000
    }
}
