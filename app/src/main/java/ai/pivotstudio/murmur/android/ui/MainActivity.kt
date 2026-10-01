package ai.pivotstudio.murmur.android.ui

import ai.pivotstudio.murmur.android.asr.SherpaMoonshineEngine
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Phase 1 proof-of-concept screen: press and hold anywhere to record, release
 * to transcribe. Output goes to Logcat (tag "Murmur/Dictation") and is also
 * mirrored on-screen for convenience during bring-up.
 *
 * First launch runs a download gate: [ModelDownloader] fetches the ~120MB
 * Moonshine + Silero VAD model files from the repo's GitHub Release into
 * app-private storage, so the APK itself stays small to download (matching
 * Wispr Flow's small-APK feel, even though — unlike Wispr Flow — this app's
 * transcription stays fully on-device/offline after that one-time fetch).
 * Subsequent launches skip straight to loading since the files already
 * exist on disk (see [ModelDownloader.isComplete]).
 *
 * This is NOT the shipping UI (no overlay bubble, no background service) —
 * see PLAN.md Phase 1 item 6 for what "done" means here, and Phase 2 for
 * the real floating-bubble + IME trigger.
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: DictationController
    private var statusText = mutableStateOf("Preparing...")
    private var downloadProgress = mutableStateOf<Float?>(null)

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        statusText.value = if (granted) "Hold to talk" else "Mic permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val audioCapture = AudioCapture(this)
        val engine = SherpaMoonshineEngine(this)
        val segmenter = SpeechSegmenter(this)
        val downloader = ModelDownloader(this)
        controller = DictationController(audioCapture, engine, segmenter)

        lifecycleScope.launch {
            if (!downloader.isComplete()) {
                statusText.value = "Downloading speech model (~120MB, one-time)..."
                try {
                    downloader.ensureDownloaded { progress ->
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
                    statusText.value = "Download failed: ${e.message}. Check connection and reopen the app."
                    return@launch
                }
            }
            downloadProgress.value = null
            statusText.value = "Loading Moonshine Tiny EN..."
            engine.load()
            statusText.value = if (audioCapture.hasMicPermission()) {
                "Hold to talk"
            } else {
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                "Requesting mic permission..."
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DictationScreen(
                        status = statusText.value,
                        downloadProgress = downloadProgress.value,
                        onPressStart = { controller.startListening(lifecycleScope) },
                        onPressEnd = { controller.stopListening() },
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DictationScreen(
    status: String,
    downloadProgress: Float?,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
) {
    var isHeld by remember { mutableStateOf(false) }

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
        }
    }
}
