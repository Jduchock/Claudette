package com.duchock.claudette.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.util.Collections

/**
 * On-device wake-word detection with openWakeWord, ported faithfully from the reference
 * Python streaming implementation (openwakeword/utils.py :: AudioFeatures).
 *
 * Pipeline, driven one 80 ms / 1280-sample frame at a time:
 *   raw audio ── melspectrogram.onnx ──▶ 8 mel frames (×32)   [transform: v/10 + 2]
 *   last 76 mel frames ── embedding_model.onnx ──▶ 1 embedding (×96)
 *   last 16 embeddings ── claudette.onnx ──▶ wake probability
 *
 * Constants verified against the actual models on a workstation:
 *   • melspec( last 1280+480 samples ) → exactly 8 mel frames
 *   • embedding window = 76 mel frames, one new embedding per frame
 *   • wake model input = [1, 16, 96], output = [1, 1] probability
 *   • klau_dette scores ≈ 0.001 on noise (sanity-checked end to end)
 *
 * Models live in assets/models/. If any is missing the detector runs in NO-OP mode.
 */
class OpenWakeWordDetector(
    private val context: Context,
    private val threshold: Float = 0.5f
) : WakeWordDetector {

    override val frameSize: Int = 1280 // 80 ms @ 16 kHz — one streaming step

    // --- ONNX ---
    private var env: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var embSession: OrtSession? = null
    private var wwSession: OrtSession? = null
    private var melInputName = "input"
    private var embInputName = "input_1"
    private var wwInputName = "input"
    private var ready = false

    // --- streaming state ---
    private val rawTail = ShortArray(MEL_INPUT_SAMPLES)   // last 1760 raw samples
    private var rawFilled = 0                              // valid samples currently in rawTail
    private val melBuffer = ArrayDeque<FloatArray>()       // rows of 32 mels
    private val featureBuffer = ArrayDeque<FloatArray>()   // rows of 96-dim embeddings
    private var cooldown = 0                               // frames to suppress repeat fires
    private var peakDbg = 0f                               // DEBUG: running max wake score

    override fun initialize(): Boolean {
        if (!assetExists("models/$MEL") || !assetExists("models/$EMB") || !assetExists("models/$WW")) {
            Log.w(TAG, "Models missing in assets/models/. NO-OP until present.")
            ready = false
            return false
        }
        return try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            melSession = e.createSession(readAsset("models/$MEL"), opts)
            embSession = e.createSession(readAsset("models/$EMB"), opts)
            wwSession = e.createSession(readAsset("models/$WW"), opts)
            melInputName = melSession!!.inputNames.first()
            embInputName = embSession!!.inputNames.first()
            wwInputName = wwSession!!.inputNames.first()
            env = e
            // seed the mel buffer with ones(76,32), matching the reference implementation
            melBuffer.clear(); featureBuffer.clear()
            repeat(MEL_WINDOW) { melBuffer.addLast(FloatArray(N_MELS) { 1f }) }
            rawFilled = 0; cooldown = 0
            ready = true
            Log.i(TAG, "openWakeWord initialized (mel='$melInputName', emb='$embInputName', ww='$wwInputName').")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Init failed", t); ready = false; false
        }
    }

    override fun process(frame: ShortArray): WakeResult {
        if (!ready) return NO_DETECTION
        return try {
            appendRaw(frame)                       // slide the 1760-sample window
            val mel = runMelspectrogram()          // ~8 new mel frames (×32)
            for (row in mel) melBuffer.addLast(row)
            while (melBuffer.size > MEL_MAX) melBuffer.removeFirst()

            val embedding = runEmbedding()         // last 76 mel frames → 96-dim
            if (embedding != null) {
                featureBuffer.addLast(embedding)
                while (featureBuffer.size > FEATURE_MAX) featureBuffer.removeFirst()
            }

            if (cooldown > 0) cooldown--

            if (featureBuffer.size >= WW_WINDOW) {
                val score = runWakeWord()
                if (score > peakDbg) { peakDbg = score; Log.i(TAG, "DBG wake score peak=%.4f".format(score)) }
                if (score >= threshold && cooldown == 0) {
                    cooldown = COOLDOWN_FRAMES
                    return WakeResult(true, score, "claudette")
                }
                return WakeResult(false, score)
            }
            NO_DETECTION
        } catch (t: Throwable) {
            Log.e(TAG, "process() error", t); NO_DETECTION
        }
    }

    // --- raw audio rolling window (keep the most recent MEL_INPUT_SAMPLES) ---
    private fun appendRaw(frame: ShortArray) {
        val n = frame.size
        if (n >= rawTail.size) {
            System.arraycopy(frame, n - rawTail.size, rawTail, 0, rawTail.size)
            rawFilled = rawTail.size
        } else {
            val keep = minOf(rawFilled, rawTail.size - n)
            System.arraycopy(rawTail, rawFilled - keep, rawTail, 0, keep) // shift tail left
            System.arraycopy(frame, 0, rawTail, keep, n)                  // append new frame
            rawFilled = keep + n
        }
    }

    // --- melspectrogram.onnx: [1, samples] float -> [T,1,1,32], squeeze, v/10+2 ---
    private fun runMelspectrogram(): Array<FloatArray> {
        val e = env!!
        val audio = FloatArray(rawFilled) { rawTail[it].toFloat() }
        OnnxTensor.createTensor(e, FloatBuffer.wrap(audio), longArrayOf(1, rawFilled.toLong())).use { input ->
            melSession!!.run(Collections.singletonMap(melInputName, input)).use { res ->
                val t = res[0] as OnnxTensor
                val frames = (t.info.shape.fold(1L) { a, d -> a * d } / N_MELS).toInt()  // frames = total mel elements / 32 mels-per-frame (output is [1,1,T,32]; shape[0] was the batch dim, not T)
                val fb = t.floatBuffer
                val out = Array(frames) { FloatArray(N_MELS) }
                var i = 0
                for (f in 0 until frames) for (m in 0 until N_MELS) out[f][m] = fb.get(i++) / 10f + 2f
                return out
            }
        }
    }

    // --- embedding_model.onnx: last 76 mel frames [1,76,32,1] -> [1,1,1,96] ---
    private fun runEmbedding(): FloatArray? {
        if (melBuffer.size < MEL_WINDOW) return null
        val e = env!!
        val flat = FloatArray(MEL_WINDOW * N_MELS)
        val rows = melBuffer.toList()
        val start = rows.size - MEL_WINDOW
        var i = 0
        for (r in start until rows.size) {
            val row = rows[r]
            for (m in 0 until N_MELS) flat[i++] = row[m]
        }
        OnnxTensor.createTensor(e, FloatBuffer.wrap(flat), longArrayOf(1, MEL_WINDOW.toLong(), N_MELS.toLong(), 1))
            .use { input ->
                embSession!!.run(Collections.singletonMap(embInputName, input)).use { res ->
                    val fb = (res[0] as OnnxTensor).floatBuffer
                    return FloatArray(EMB_DIM) { fb.get(it) }
                }
            }
    }

    // --- claudette.onnx: last 16 embeddings [1,16,96] -> [1,1] ---
    private fun runWakeWord(): Float {
        val e = env!!
        val flat = FloatArray(WW_WINDOW * EMB_DIM)
        val embs = featureBuffer.toList()
        val start = embs.size - WW_WINDOW
        var i = 0
        for (r in start until embs.size) {
            val emb = embs[r]
            for (d in 0 until EMB_DIM) flat[i++] = emb[d]
        }
        OnnxTensor.createTensor(e, FloatBuffer.wrap(flat), longArrayOf(1, WW_WINDOW.toLong(), EMB_DIM.toLong()))
            .use { input ->
                wwSession!!.run(Collections.singletonMap(wwInputName, input)).use { res ->
                    return (res[0] as OnnxTensor).floatBuffer.get(0)
                }
            }
    }

    override fun close() {
        runCatching { melSession?.close() }
        runCatching { embSession?.close() }
        runCatching { wwSession?.close() }
        melSession = null; embSession = null; wwSession = null
        env = null; ready = false
        melBuffer.clear(); featureBuffer.clear()
    }

    private fun assetExists(path: String): Boolean =
        runCatching { context.assets.open(path).use { true } }.getOrDefault(false)

    private fun readAsset(path: String): ByteArray =
        context.assets.open(path).use { it.readBytes() }

    companion object {
        private const val TAG = "OpenWakeWord"
        private const val MEL = "melspectrogram.onnx"
        private const val EMB = "embedding_model.onnx"
        // Trained model file in assets/models/ (rename here if yours differs).
        private const val WW = "claudette.onnx"

        private const val N_MELS = 32
        private const val MEL_WINDOW = 76            // mel frames per embedding
        private const val EMB_DIM = 96
        private const val WW_WINDOW = 16             // embeddings per wake prediction
        private const val MEL_INPUT_SAMPLES = 1280 + 160 * 3 // 1760: frame + left context
        private const val MEL_MAX = 970              // ~10 s of mel history
        private const val FEATURE_MAX = 120          // ~10 s of embedding history
        private const val COOLDOWN_FRAMES = 25       // ~2 s refractory after a detection

        private val NO_DETECTION = WakeResult(false, 0f)
    }
}
