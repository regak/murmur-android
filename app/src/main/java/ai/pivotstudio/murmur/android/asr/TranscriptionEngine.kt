package ai.pivotstudio.murmur.android.asr

/**
 * Seam between the dictation pipeline and whatever ASR backend is active.
 *
 * Mirrors the macOS app's `TranscriptionEngine` protocol: swapping the model
 * (Moonshine Tiny -> Base, or a future engine) means implementing this
 * interface only. [ai.pivotstudio.murmur.android.core.DictationController]
 * never changes.
 */
interface TranscriptionEngine {
    /** Human-readable name for logs / settings UI. */
    val name: String

    /** Must be called once, off the main thread, before [transcribe]. */
    suspend fun load()

    /**
     * Transcribe a single finished utterance (post-VAD segment).
     *
     * @param pcm16kMono 16kHz mono 16-bit PCM samples, already resampled by
     *   [ai.pivotstudio.murmur.android.core.AudioCapture].
     */
    suspend fun transcribe(pcm16kMono: ShortArray): String

    /** Release native resources (sherpa-onnx sessions, etc). */
    fun close()
}
