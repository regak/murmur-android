package ai.pivotstudio.murmur.android.asr

import ai.pivotstudio.murmur.android.core.ModelDownloader
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
 * see PLAN.md).
 *
 * Model files are NOT bundled in the APK (that made the download ~140MB+
 * for a ~120MB model that Wispr Flow doesn't even ship, since Wispr Flow
 * is cloud-based). Instead [ModelDownloader] fetches them into app-private
 * storage on first launch, and this engine loads from that filesystem path
 * via sherpa-onnx's `assetManager = null` / file-path constructor mode —
 * see MainActivity for the download-gate that runs before load().
 */
class SherpaMoonshineEngine(
    private val context: Context,
) : TranscriptionEngine {

    override val name: String = "sherpa-onnx / moonshine-tiny-en-int8"

    private var recognizer: OfflineRecognizer? = null

    override suspend fun load() = withContext(Dispatchers.IO) {
        val modelDir = ModelDownloader(context).moonshineDir.absolutePath
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = "$modelDir/preprocess.onnx",
                    encoder = "$modelDir/encode.int8.onnx",
                    uncachedDecoder = "$modelDir/uncached_decode.int8.onnx",
                    cachedDecoder = "$modelDir/cached_decode.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                numThreads = 2,
                debug = false,
            ),
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
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

    companion object {
        const val SAMPLE_RATE_HZ = 16000
    }
}
