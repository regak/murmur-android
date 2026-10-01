package ai.pivotstudio.murmur.android.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the ASR model assets (Moonshine Tiny EN + Silero VAD, ~120MB
 * total) into app-private storage on first launch, instead of bundling them
 * inside the APK.
 *
 * Why: bundling the models made the APK ~140-230MB to *download*, even
 * though the on-disk footprint is the same either way. Wispr Flow's APK is
 * small (~35-57MB) because it ships NO model at all — it streams audio to
 * a cloud Whisper endpoint. We can't do that (this app is on-device/offline
 * by design), but we CAN get the small-download feel by deferring the
 * model fetch to first run, same trick most on-device AI apps use
 * (ChatGPT's local models, ML Kit downloadable modules, etc.).
 *
 * Files are fetched from a GitHub Release (see repo release "models-v1")
 * rather than Google Play's Asset Delivery API, to keep this buildable
 * and installable outside the Play Store (sideload-friendly, matches how
 * this project is being distributed during development).
 */
class ModelDownloader(private val context: Context) {

    data class Progress(val fileName: String, val fileIndex: Int, val fileCount: Int, val bytesDone: Long, val bytesTotal: Long)

    private val modelsRoot: File
        get() = File(context.filesDir, "models")

    val moonshineDir: File
        get() = File(modelsRoot, "sherpa-onnx-moonshine-tiny-en-int8")

    val vadDir: File
        get() = File(modelsRoot, "vad")

    fun isComplete(): Boolean = FILES.all { (dir, name) -> resolvedDir(dir).let { File(it, name).exists() } }

    private fun resolvedDir(dir: String) = if (dir == "vad") vadDir else moonshineDir

    /**
     * Downloads any missing files, skipping ones already present (so a
     * killed/interrupted download resumes cheaply — no re-fetching
     * completed files). Reports progress via [onProgress].
     */
    suspend fun ensureDownloaded(onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        moonshineDir.mkdirs()
        vadDir.mkdirs()

        val missing = FILES.filterIndexed { _, (dir, name) -> !File(resolvedDir(dir), name).exists() }
        missing.forEachIndexed { index, (dir, name) ->
            val destFile = File(resolvedDir(dir), name)
            val tmpFile = File(destFile.parentFile, "${name}.part")
            downloadToFile(
                url = "$RELEASE_BASE_URL/$name",
                dest = tmpFile,
            ) { bytesDone, bytesTotal ->
                onProgress(Progress(name, index + 1, missing.size, bytesDone, bytesTotal))
            }
            tmpFile.renameTo(destFile)
        }
    }

    private fun downloadToFile(url: String, dest: File, onBytes: (Long, Long) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        connection.connect()
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    done += read
                    onBytes(done, total)
                }
            }
        }
    }

    companion object {
        // GitHub Release direct-download base URL (see repo release "models-v1").
        private const val RELEASE_BASE_URL =
            "https://github.com/regak/murmur-android/releases/download/models-v1"

        private val FILES = listOf(
            "moonshine" to "preprocess.onnx",
            "moonshine" to "encode.int8.onnx",
            "moonshine" to "uncached_decode.int8.onnx",
            "moonshine" to "cached_decode.int8.onnx",
            "moonshine" to "tokens.txt",
            "vad" to "silero_vad.onnx",
        )

        const val TOTAL_BYTES_APPROX = 120L * 1024 * 1024
    }
}
