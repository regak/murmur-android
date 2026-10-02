package ai.pivotstudio.murmur.android.core

import ai.pivotstudio.murmur.android.asr.EngineId
import ai.pivotstudio.murmur.android.asr.modelFiles
import ai.pivotstudio.murmur.android.asr.releaseTag
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the ASR model assets for a given [EngineId] into app-private
 * storage on first use, instead of bundling them inside the APK.
 *
 * Why: bundling the models made the APK ~140-230MB to *download*, even
 * though the on-disk footprint is the same either way. Wispr Flow's APK is
 * small (~35-57MB) because it ships NO model at all — it streams audio to
 * a cloud Whisper endpoint. We can't do that (this app is on-device/offline
 * by design), but we CAN get the small-download feel by deferring the
 * model fetch to first run, same trick most on-device AI apps use
 * (ChatGPT's local models, ML Kit downloadable modules, etc.).
 *
 * Multi-engine (model swap) support: each [EngineId]'s files live in their
 * own subdirectory (named after [EngineId.assetDirName]) and are downloaded
 * independently, on demand, the first time that engine is selected — not
 * all engines up front. Switching back to a previously-downloaded engine
 * is instant (files stay on disk; [isComplete] just finds them already
 * there). The VAD model (shared by every engine, not engine-specific) is
 * always ensured alongside whichever ASR engine's files are requested.
 *
 * Files are fetched from GitHub Releases (one release tag per engine, see
 * [EngineId.releaseTag]) rather than Google Play's Asset Delivery API, to
 * keep this buildable and installable outside the Play Store
 * (sideload-friendly, matches how this project is being distributed
 * during development).
 */
class ModelDownloader(private val context: Context) {

    data class Progress(val fileName: String, val fileIndex: Int, val fileCount: Int, val bytesDone: Long, val bytesTotal: Long)

    private val modelsRoot: File
        get() = File(context.filesDir, "models")

    fun dirFor(engine: EngineId): File = File(modelsRoot, engine.assetDirName)

    val vadDir: File
        get() = File(modelsRoot, "vad")

    fun isComplete(engine: EngineId): Boolean {
        val dir = dirFor(engine)
        return engine.modelFiles().all { File(dir, it).exists() } && vadFilesComplete()
    }

    private fun vadFilesComplete(): Boolean = File(vadDir, "silero_vad.onnx").exists()

    /**
     * Downloads any missing files for [engine] (plus the shared VAD model
     * if not already present), skipping files already on disk — so
     * switching to a previously-used engine, or resuming an interrupted
     * download, never re-fetches what's already there. Reports progress
     * via [onProgress].
     */
    suspend fun ensureDownloaded(engine: EngineId, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val engineDir = dirFor(engine)
        engineDir.mkdirs()
        vadDir.mkdirs()

        val engineBase = "$RELEASE_BASE_URL_PREFIX/${engine.releaseTag()}"
        val jobs = engine.modelFiles().map { name -> Triple(engineDir, name, "$engineBase/$name") } +
            if (vadFilesComplete()) emptyList() else listOf(Triple(vadDir, VAD_FILE_NAME, "$RELEASE_BASE_URL_PREFIX/$VAD_RELEASE_TAG/$VAD_FILE_NAME"))

        val missing = jobs.filterNot { (dir, name, _) -> File(dir, name).exists() }
        missing.forEachIndexed { index, (dir, name, url) ->
            val destFile = File(dir, name)
            val tmpFile = File(dir, "$name.part")
            downloadToFile(url, tmpFile) { bytesDone, bytesTotal ->
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
        private const val RELEASE_BASE_URL_PREFIX = "https://github.com/regak/murmur-android/releases/download"
        private const val VAD_RELEASE_TAG = "models-v1"
        private const val VAD_FILE_NAME = "silero_vad.onnx"
    }
}
