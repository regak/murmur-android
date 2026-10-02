package ai.pivotstudio.murmur.android.asr

import ai.pivotstudio.murmur.android.core.ModelDownloader
import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parakeet TDT-CTC 110M (English), INT8, via sherpa-onnx — the "more
 * accurate" alternative ASR engine, selectable in Settings alongside
 * [SherpaMoonshineEngine] (see [EngineId] for the swappable-model registry).
 *
 * Why this model specifically: it's the same model FAMILY the macOS/Windows
 * `murmur-youtube` sibling apps use (NVIDIA Parakeet via FluidAudio on
 * Windows, where there's no Apple SpeechAnalyzer equivalent) — picked when
 * Alexander asked "is it possible to use a similar model to the one used
 * in the macOS original repo". The 110M variant was chosen over the full
 * 0.6B Parakeet (~478MB) to keep the download in the same size class as
 * Moonshine Tiny (~126MB vs ~120MB) rather than a 4x bigger one-time
 * download — 0.6B remains a candidate to add later as a third option if
 * 110M's accuracy still isn't enough.
 *
 * Single ONNX graph (`model.int8.onnx`) + `tokens.txt`, unlike Moonshine's
 * 4-file preprocessor/encoder/decoder split — a CTC model, not an
 * encoder-decoder transducer, so sherpa-onnx's NeMo CTC config path only
 * needs the one model file.
 */
class SherpaParakeetEngine(
    private val context: Context,
) : TranscriptionEngine {

    override val name: String = "sherpa-onnx / parakeet-tdt-ctc-110m-en-int8"

    private var recognizer: OfflineRecognizer? = null

    override suspend fun load() = withContext(Dispatchers.IO) {
        val modelDir = ModelDownloader(context).dirFor(EngineId.PARAKEET_110M_EN).absolutePath
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                numThreads = 2,
                debug = false,
            ),
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    override suspend fun transcribe(pcm16kMono: ShortArray): String = withContext(Dispatchers.Default) {
        val engine = recognizer ?: error("SherpaParakeetEngine.load() was not called")
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
