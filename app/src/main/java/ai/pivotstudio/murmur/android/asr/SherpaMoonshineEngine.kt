package ai.pivotstudio.murmur.android.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Default ASR engine: Moonshine Tiny EN, INT8, via sherpa-onnx.
 *
 * Chosen over Whisper/Parakeet/Zipformer-streaming for raw speed to match
 * Wispr Flow's snappy feel on a phone CPU — see README.md for the
 * benchmark data behind this pick (RTF ~0.05 on a Samsung Galaxy S10,
 * fastest of 16 models in the public voiceping.net on-device ASR benchmark).
 *
 * English-only by design for v1 (Swahili/multilingual explicitly deferred,
 * see PLAN.md). Swapping to [MOONSHINE_BASE] for higher accuracy is a
 * one-line [modelDir] change; nothing else in the pipeline needs to know.
 *
 * Model assets are NOT committed to git (too large for the repo) — fetch
 * them per app/src/main/assets/README.md before building.
 */
class SherpaMoonshineEngine(
    private val context: Context,
    private val modelDir: String = MOONSHINE_TINY_EN,
) : TranscriptionEngine {

    override val name: String = "sherpa-onnx / $modelDir"

    private var recognizer: OfflineRecognizer? = null

    override suspend fun load() = withContext(Dispatchers.IO) {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = assetPath("preprocess.onnx"),
                    encoder = assetPath("encode.int8.onnx"),
                    uncachedDecoder = assetPath("uncached_decode.int8.onnx"),
                    cachedDecoder = assetPath("cached_decode.int8.onnx"),
                ),
                tokens = assetPath("tokens.txt"),
                numThreads = 2,
                debug = false,
            ),
        )
        recognizer = OfflineRecognizer(assetManager = context.assets, config = config)
    }

    override suspend fun transcribe(pcm16kMono: ShortArray): String = withContext(Dispatchers.Default) {
        val engine = recognizer ?: error("SherpaMoonshineEngine.load() was not called")
        val stream = engine.createStream()
        try {
            val floatSamples = FloatArray(pcm16kMono.size) { pcm16kMono[it] / 32768.0f }
            stream.acceptWaveform(floatSamples, sampleRate = SAMPLE_RATE_HZ)
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    private fun assetPath(file: String) = "$modelDir/$file"

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val MOONSHINE_TINY_EN = "sherpa-onnx-moonshine-tiny-en-int8"
        const val MOONSHINE_BASE_EN = "sherpa-onnx-moonshine-base-en-int8"
    }
}
