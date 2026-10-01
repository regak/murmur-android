package ai.pivotstudio.murmur.android.core

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import android.content.Context

/**
 * Wraps sherpa-onnx's Silero VAD so [DictationController] doesn't just send
 * everything between press and release to the ASR engine verbatim.
 *
 * Phase 1 use: trims leading/trailing silence from the held-button segment
 * before it reaches Moonshine — a user who holds the button, pauses half a
 * second before speaking, then pauses again before releasing, shouldn't pay
 * for that silence in encoder time or get it mis-transcribed as noise.
 *
 * This also lays the groundwork for a later "tap to start, auto-stop on
 * silence" trigger mode (Phase 3 settings), which needs exactly this speech/
 * silence segmentation rather than a manual release.
 * Model file is NOT bundled in the APK — [ModelDownloader] fetches it on
 * first launch alongside the Moonshine ASR files. See that class and
 * MainActivity's download-gate for details.
 */
class SpeechSegmenter(context: Context) {

    private val vad: Vad = Vad(
        assetManager = null,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "${ModelDownloader(context).vadDir.absolutePath}/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.1f,
                windowSize = WINDOW_SIZE_SAMPLES,
                maxSpeechDuration = 30.0f,
            ),
            sampleRate = SAMPLE_RATE_HZ,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    /**
     * Feed the full held-button recording in. VAD windows are fixed-size
     * ([WINDOW_SIZE_SAMPLES]); the final partial window (if any) is padded
     * with silence so no trailing speech is dropped.
     */
    fun accept(pcm16kMono: ShortArray) {
        var offset = 0
        while (offset < pcm16kMono.size) {
            val end = minOf(offset + WINDOW_SIZE_SAMPLES, pcm16kMono.size)
            val window = FloatArray(WINDOW_SIZE_SAMPLES)
            for (i in offset until end) {
                window[i - offset] = pcm16kMono[i] / 32768.0f
            }
            vad.acceptWaveform(window)
            offset = end
        }
        vad.flush()
    }

    /**
     * Returns the speech portion only, as one concatenated segment, trimming
     * leading/trailing silence. Returns an empty array if VAD found no speech
     * (e.g. the user held the button but said nothing).
     */
    fun extractSpeech(): ShortArray {
        val samples = ArrayList<Short>()
        while (!vad.empty()) {
            val segment = vad.front()
            // segment.samples: FloatArray in [-1, 1] — convert back to PCM16.
            for (s in segment.samples) {
                samples.add((s * 32768.0f).toInt().coerceIn(-32768, 32767).toShort())
            }
            vad.pop()
        }
        return samples.toShortArray()
    }

    fun reset() {
        vad.reset()
    }

    fun close() {
        vad.release()
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val WINDOW_SIZE_SAMPLES = 512
    }
}
