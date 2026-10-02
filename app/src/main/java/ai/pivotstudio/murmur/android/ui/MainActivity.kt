package ai.pivotstudio.murmur.android.ui

import ai.pivotstudio.murmur.android.asr.EngineId
import ai.pivotstudio.murmur.android.asr.EnginePreferences
import ai.pivotstudio.murmur.android.asr.createEngine
import ai.pivotstudio.murmur.android.core.AudioCapture
import ai.pivotstudio.murmur.android.core.DictationController
import ai.pivotstudio.murmur.android.core.ModelDownloader
import ai.pivotstudio.murmur.android.core.SpeechSegmenter
import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Phase 1 proof-of-concept screen: press and hold anywhere to record, release
 * to transcribe. The transcript is shown directly on screen (not just
 * Logcat) — an earlier build only Log.i'd it, which is invisible on a real
 * device with no way to view Logcat and looked exactly like "transcription
 * does nothing" (confirmed by real-device testing). [DictationController]'s
 * onResult callback now drives [lastResult], which this screen renders.
 *
 * Model swap (Settings): the user can pick between [EngineId.MOONSHINE_TINY_EN]
 * (fast, small) and [EngineId.PARAKEET_110M_EN] (same model family as the
 * macOS/Windows Murmur apps' Parakeet engine, picked when Moonshine was
 * found to miss words) via the gear icon -> Settings screen below.
 * [EnginePreferences] persists the choice; switching re-runs the download
 * gate for whichever engine's files aren't on disk yet, then reloads the
 * engine in place — a previously-used engine's files are never re-fetched.
 *
 * First launch (and first use of a newly-selected engine) runs a download
 * gate: [ModelDownloader] fetches that engine's model files (+ shared VAD)
 * from the repo's GitHub Releases into app-private storage, so the APK
 * itself stays small to download (matching Wispr Flow's small-APK feel,
 * even though — unlike Wispr Flow — this app's transcription stays fully
 * on-device/offline after that one-time fetch).
 *
 * IMPORTANT: [TranscriptionEngine] implementations and [SpeechSegmenter]
 * both construct native sherpa-onnx objects that eagerly open their model
 * files from disk. They must NOT be constructed until after the download
 * gate confirms the files exist — constructing them against missing files
 * crashes natively (not a catchable Kotlin exception) and takes the whole
 * process down, which looks like "the app opens then instantly closes".
 * This was exactly the bug in the first release of this screen.
 *
 * This is NOT the shipping UI (no overlay bubble, no background service) —
 * see PLAN.md Phase 1 item 6 for what "done" means here, and Phase 2 for
 * the real floating-bubble + IME trigger.
 */
class MainActivity : ComponentActivity() {

    private var controller: DictationController? = null
    private var statusText = mutableStateOf("Preparing...")
    private var downloadProgress = mutableStateOf<Float?>(null)
    private var lastResult = mutableStateOf("")
    private var showSettings = mutableStateOf(false)
    private var currentEngine = mutableStateOf(EngineId.DEFAULT)
    private lateinit var enginePrefs: EnginePreferences
    private lateinit var downloader: ModelDownloader

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        statusText.value = if (granted) "Hold to talk" else "Mic permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enginePrefs = EnginePreferences(this)
        downloader = ModelDownloader(this)
        currentEngine.value = enginePrefs.selected

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (showSettings.value) {
                        SettingsScreen(
                            current = currentEngine.value,
                            downloadedEngines = EngineId.entries.filter { downloader.isComplete(it) }.toSet(),
                            onSelect = { selected -> onEngineSelected(selected) },
                            onBack = { showSettings.value = false },
                        )
                    } else {
                        DictationScreen(
                            status = statusText.value,
                            downloadProgress = downloadProgress.value,
                            lastResult = lastResult.value,
                            engineLabel = currentEngine.value.displayName,
                            onPressStart = { controller?.startListening(lifecycleScope) },
                            onPressEnd = { controller?.stopListening() },
                            onOpenSettings = { showSettings.value = true },
                        )
                    }
                }
            }
        }

        loadEngine(currentEngine.value)
    }

    private fun onEngineSelected(selected: EngineId) {
        if (selected == currentEngine.value) {
            showSettings.value = false
            return
        }
        enginePrefs.selected = selected
        currentEngine.value = selected
        showSettings.value = false
        controller = null // stop accepting hold-to-talk until the new engine finishes loading
        loadEngine(selected)
    }

    private fun loadEngine(engineId: EngineId) {
        lifecycleScope.launch {
            if (!downloader.isComplete(engineId)) {
                statusText.value = "Downloading ${engineId.displayName}..."
                try {
                    downloader.ensureDownloaded(engineId) { progress ->
                        val fraction = if (progress.bytesTotal > 0) {
                            progress.bytesDone.toFloat() / progress.bytesTotal.toFloat()
                        } else {
                            0f
                        }
                        downloadProgress.value = fraction
                        statusText.value =
                            "Downloading model ${progress.fileIndex}/${progress.fileCount}: ${progress.fileName}"
                    }
                } catch (e: Exception) {
                    Log.e("Murmur/Download", "Model download failed", e)
                    statusText.value = "Download failed: ${e.message}. Check connection and try again."
                    return@launch
                }
            }
            downloadProgress.value = null

            // Safe to construct native sherpa-onnx objects now — files exist on disk.
            statusText.value = "Loading ${engineId.displayName}..."
            val audioCapture = AudioCapture(this@MainActivity)
            val engine = engineId.createEngine(this@MainActivity)
            val segmenter = SpeechSegmenter(this@MainActivity)
            try {
                engine.load()
            } catch (e: Exception) {
                Log.e("Murmur/Engine", "Failed to load ASR engine", e)
                statusText.value = "Engine load failed: ${e.message}. Try reinstalling or picking a different model."
                return@launch
            }
            controller = DictationController(audioCapture, engine, segmenter) { result ->
                lastResult.value = when (result) {
                    is DictationController.Result.Transcript -> result.text
                    is DictationController.Result.NoSpeechDetected ->
                        "(no speech detected — try holding longer / speaking louder)"
                    is DictationController.Result.Error ->
                        "Transcription error: ${result.message}"
                }
            }

            statusText.value = if (audioCapture.hasMicPermission()) {
                "Hold to talk"
            } else {
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                "Requesting mic permission..."
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DictationScreen(
    status: String,
    downloadProgress: Float?,
    lastResult: String,
    engineLabel: String,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var isHeld by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onOpenSettings) { Text("Model: $engineLabel  \u2699") }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .pointerInput(downloadProgress) {
                    if (downloadProgress != null) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            isHeld = true
                            onPressStart()
                            tryAwaitRelease()
                            isHeld = false
                            onPressEnd()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = if (isHeld) "Listening... release to transcribe" else status)
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }
                if (!isHeld && downloadProgress == null && lastResult.isNotEmpty()) {
                    Text(
                        text = lastResult,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SettingsScreen(
    current: EngineId,
    downloadedEngines: Set<EngineId>,
    onSelect: (EngineId) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(text = "Speech model", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "Switching re-downloads only if this model isn't already on your device.")
        Spacer(modifier = Modifier.height(16.dp))

        EngineId.entries.forEach { engine ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = engine == current, onClick = { onSelect(engine) })
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(text = engine.displayName, fontWeight = FontWeight.Medium)
                        Text(text = engine.subtitle, fontSize = 13.sp)
                        Text(
                            text = if (engine in downloadedEngines) "Downloaded" else "Not downloaded yet",
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}
