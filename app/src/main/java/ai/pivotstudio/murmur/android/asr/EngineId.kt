package ai.pivotstudio.murmur.android.asr

import android.content.Context
import kotlin.math.max

/**
 * Registry of selectable ASR engines/models — the "swap models" feature.
 *
 * Each [EngineId] is a self-contained spec: which files to download, where
 * sherpa-onnx expects them, how big they are, and a short human-readable
 * accuracy/speed tradeoff blurb for the settings screen. Adding a new engine
 * later means adding one entry here plus one `TranscriptionEngine`
 * implementation — [ai.pivotstudio.murmur.android.core.DictationController]
 * and the UI never need to change.
 */
enum class EngineId(
    val displayName: String,
    val subtitle: String,
    val approxDownloadBytes: Long,
    val assetDirName: String,
) {
    MOONSHINE_TINY_EN(
        displayName = "Moonshine Tiny (English)",
        subtitle = "Fastest, smallest download (~120MB). Good for short dictation.",
        approxDownloadBytes = 120L * 1024 * 1024,
        assetDirName = "sherpa-onnx-moonshine-tiny-en-int8",
    ),
    PARAKEET_110M_EN(
        displayName = "Parakeet TDT-CTC 110M (English)",
        subtitle = "More accurate, ~126MB download. Same model family the macOS/Windows " +
            "Murmur apps use (NVIDIA Parakeet). Recommended if Moonshine is missing words.",
        approxDownloadBytes = 126L * 1024 * 1024,
        assetDirName = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
    ),
    ;

    companion object {
        val DEFAULT = MOONSHINE_TINY_EN
    }
}

/** Which files each [EngineId] needs, for [ai.pivotstudio.murmur.android.core.ModelDownloader]. */
fun EngineId.modelFiles(): List<String> = when (this) {
    EngineId.MOONSHINE_TINY_EN -> listOf(
        "preprocess.onnx",
        "encode.int8.onnx",
        "uncached_decode.int8.onnx",
        "cached_decode.int8.onnx",
        "tokens.txt",
    )
    EngineId.PARAKEET_110M_EN -> listOf(
        "model.int8.onnx",
        "tokens.txt",
    )
}

/** GitHub Release tag each [EngineId]'s files live under (see repo Releases). */
fun EngineId.releaseTag(): String = when (this) {
    EngineId.MOONSHINE_TINY_EN -> "models-v1"
    EngineId.PARAKEET_110M_EN -> "models-parakeet110m-v1"
}

/** Constructs the right [TranscriptionEngine] implementation for this [EngineId]. */
fun EngineId.createEngine(context: Context): TranscriptionEngine = when (this) {
    EngineId.MOONSHINE_TINY_EN -> SherpaMoonshineEngine(context)
    EngineId.PARAKEET_110M_EN -> SherpaParakeetEngine(context)
}

/**
 * Persists which [EngineId] the user picked (Settings), defaulting to
 * [EngineId.DEFAULT] on first run. Separate from [ai.pivotstudio.murmur.android.core.ModelDownloader]
 * so the UI can read/write the selection without touching download logic.
 */
class EnginePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("murmur_prefs", Context.MODE_PRIVATE)

    var selected: EngineId
        get() = EngineId.entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: EngineId.DEFAULT
        set(value) = prefs.edit().putString(KEY, value.name).apply()

    companion object {
        private const val KEY = "selected_engine"
    }
}
