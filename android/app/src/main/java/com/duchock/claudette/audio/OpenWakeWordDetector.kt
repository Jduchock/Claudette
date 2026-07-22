package com.duchock.claudette.audio

import android.content.Context
import android.util.Log

/**
 * openWakeWord runs three ONNX models in sequence:
 *   1) melspectrogram.onnx  - raw audio  -> mel spectrogram
 *   2) embedding_model.onnx - mel        -> 96-dim speech embedding (shared model)
 *   3) <wakeword>.onnx      - embeddings -> wake probability   (the trained "Claudette" model)
 *
 * Drop the three files into app/src/main/assets/models/ and set [WAKEWORD_MODEL].
 * Until the models are present this detector runs in NO-OP mode so the whole listening
 * pipeline (service, notification, audio capture, UI) can be built and tested now.
 * Use the "Test wake" button in the app to exercise the downstream flow meanwhile.
 */
class OpenWakeWordDetector(
    private val context: Context,
    @Suppress("unused") private val threshold: Float = 0.5f
) : WakeWordDetector {

    override val frameSize: Int = 1280 // 80 ms @ 16 kHz

    private var ready = false
    // TODO(Phase 1b): OrtEnvironment + three OrtSession fields + rolling buffers.

    override fun initialize(): Boolean {
        val hasModels = assetExists("models/$MELSPEC_MODEL") &&
            assetExists("models/$EMBEDDING_MODEL") &&
            assetExists("models/$WAKEWORD_MODEL")
        if (!hasModels) {
            Log.w(TAG, "Models not found in assets/models/. Running NO-OP until the " +
                "trained 'Claudette' model is added.")
            ready = false
            return false
        }
        // TODO(Phase 1b): create OrtEnvironment, load the three OrtSessions,
        //                 allocate the mel + embedding sliding windows.
        ready = true
        Log.i(TAG, "openWakeWord initialized.")
        return true
    }

    override fun process(frame: ShortArray): WakeResult {
        if (!ready) return NO_DETECTION
        // TODO(Phase 1b): mel -> embedding -> wakeword; slide the embedding window;
        //                 compare max probability against [threshold].
        return NO_DETECTION
    }

    override fun close() {
        // TODO(Phase 1b): close OrtSessions and OrtEnvironment.
        ready = false
    }

    private fun assetExists(path: String): Boolean =
        runCatching { context.assets.open(path).use { true } }.getOrDefault(false)

    companion object {
        private const val TAG = "OpenWakeWord"
        private const val MELSPEC_MODEL = "melspectrogram.onnx"
        private const val EMBEDDING_MODEL = "embedding_model.onnx"
        // Rename to match your trained model file dropped in assets/models/
        private const val WAKEWORD_MODEL = "claudette.onnx"
        private val NO_DETECTION = WakeResult(false, 0f)
    }
}
