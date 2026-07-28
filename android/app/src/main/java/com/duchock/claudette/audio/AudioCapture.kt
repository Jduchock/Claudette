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
 */
class AudioCapture(
    private val frameSize: Int,
    private val onFrame: (ShortArray) -> Unit
) {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is verified before start()
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, frameSize * 2 * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            rec.release()
            return
        }
        recorder = rec
        running = true
        rec.startRecording()
        worker = thread(name = "claudette-audio") {
            val buf = ShortArray(frameSize)
            var dbgFrames = 0
            var dbgPeak = 0
            while (running) {
                var read = 0
                while (read < frameSize && running) {
                    val n = rec.read(buf, read, frameSize - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == frameSize) {
                    // DEBUG mic level meter: peak sample amplitude, logged ~every 2s
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
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        recorder?.run {
            runCatching { stop() }
            release()
        }
        recorder = null
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16000
    }
}
