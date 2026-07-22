package com.duchock.claudette.audio

/** Result of processing one audio frame. */
data class WakeResult(
    val detected: Boolean,
    val score: Float,
    val keyword: String? = null
)

/**
 * Abstraction over a wake-word engine so the rest of the app never depends on a
 * specific implementation. Phase 1 ships [OpenWakeWordDetector]; this seam lets us
 * drop in Vosk keyword-spotting later without touching the service.
 */
interface WakeWordDetector {
    /** One-time model/resource load. Returns true if ready to run inference. */
    fun initialize(): Boolean

    /**
     * Feed one frame of 16 kHz, mono, 16-bit PCM audio (length == [frameSize]).
     * Returns whether the wake word fired on/after this frame.
     */
    fun process(frame: ShortArray): WakeResult

    /** Samples expected per [process] call. */
    val frameSize: Int

    fun close()
}
