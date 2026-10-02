package ai.pivotstudio.murmur.android.ime

import ai.pivotstudio.murmur.android.asr.EngineId
import ai.pivotstudio.murmur.android.asr.EnginePreferences
import ai.pivotstudio.murmur.android.asr.TranscriptionEngine
import ai.pivotstudio.murmur.android.asr.createEngine
import ai.pivotstudio.murmur.android.core.AudioCapture
import ai.pivotstudio.murmur.android.core.DictationController
import ai.pivotstudio.murmur.android.core.ModelDownloader
import ai.pivotstudio.murmur.android.core.SpeechSegmenter
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Phase 2 primary injection path: a custom keyboard ("Input Method") with a
 * single mic key. Text goes directly into whatever field is focused in
 * ANY app via [InputConnection.commitText] — this is how real dictation
 * keyboards (GBoard's mic, Wispr Flow's Android client) get text into
 * other apps, and it's the only injection method that's guaranteed to
 * work regardless of which app is in front, because an IME *is* the
 * keyboard the focused field is already listening to.
 *
 * To use: Settings -> System -> Languages & input -> On-screen keyboard ->
 * Manage keyboards -> enable "Murmur". Then switch to it from any text
 * field's keyboard-switcher (globe icon / long-press space).
 *
 * Reuses the exact same pipeline as [ai.pivotstudio.murmur.android.ui.MainActivity]
 * ([AudioCapture] -> [SpeechSegmenter]/AGC -> [TranscriptionEngine] ->
 * [ai.pivotstudio.murmur.android.core.RuleBasedFormatter]) via
 * [DictationController] — this is the whole point of that interface split:
 * the IME and the in-app test screen are two different *front ends* for
 * identical dictation logic, never duplicated.
 *
 * Model loading happens once, lazily, the first time the mic key is
 * pressed in a session (not in onCreate) — this view can be inflated by
 * the system keyboard tray far more often than the user actually dictates,
 * and model loading/downloading has real cost (see [ModelDownloader]).
 *
 * This InputMethodService acts as its own minimal androidx LifecycleOwner
 * so [DictationController]/[AudioCapture] can use `lifecycleScope` exactly
 * like MainActivity does, without pulling in a full Activity.
 */
class MurmurInputMethodService : InputMethodService(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle get() = lifecycleRegistry

    private var controller: DictationController? = null
    private var loadedEngineId: EngineId? = null
    private var isLoadingEngine = false

    private lateinit var statusView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var micButton: Button

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)

        val root = FrameLayout(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        statusView = TextView(this).apply {
            text = "Tap the mic to dictate"
            textSize = 14f
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        micButton = Button(this).apply {
            text = "\uD83C\uDF99 Hold to talk"
        }
        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    onMicPressed()
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    onMicReleased()
                    true
                }
                else -> false
            }
        }

        column.addView(statusView)
        column.addView(progressBar)
        column.addView(micButton)
        root.addView(column)
        return root
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Lazily (re)load the engine the first time the keyboard is actually
        // shown with an input connection ready, not on view creation.
        ensureEngineLoaded()
    }

    private fun onMicPressed() {
        val ctrl = controller
        if (ctrl == null) {
            statusView.text = "Still loading the speech model..."
            return
        }
        statusView.text = "Listening..."
        ctrl.startListening(lifecycleScope())
    }

    private fun onMicReleased() {
        controller?.stopListening()
        statusView.text = "Transcribing..."
    }

    private fun ensureEngineLoaded() {
        val selected = EnginePreferences(this).selected
        if (loadedEngineId == selected && controller != null) return
        if (isLoadingEngine) return
        isLoadingEngine = true

        lifecycleScope().launch {
            val downloader = ModelDownloader(this@MurmurInputMethodService)
            if (!downloader.isComplete(selected)) {
                statusView.text = "Downloading ${selected.displayName}... (open the Murmur app once to pre-download so this isn't needed on first use)"
                progressBar.visibility = View.VISIBLE
                try {
                    downloader.ensureDownloaded(selected) { progress ->
                        progressBar.progress = if (progress.bytesTotal > 0) {
                            (100L * progress.bytesDone / progress.bytesTotal).toInt()
                        } else {
                            0
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "IME model download failed", e)
                    statusView.text = "Download failed. Open the Murmur app to retry."
                    isLoadingEngine = false
                    return@launch
                }
            }
            progressBar.visibility = View.GONE

            try {
                val audioCapture = AudioCapture(this@MurmurInputMethodService)
                val engine: TranscriptionEngine = selected.createEngine(this@MurmurInputMethodService)
                val segmenter = SpeechSegmenter(this@MurmurInputMethodService)
                engine.load()
                controller = DictationController(audioCapture, engine, segmenter) { result ->
                    when (result) {
                        is DictationController.Result.Transcript -> {
                            commitTextToFocusedField(result.text + " ")
                            statusView.text = "Tap the mic to dictate"
                        }
                        is DictationController.Result.NoSpeechDetected ->
                            statusView.text = "(no speech detected — tap the mic to try again)"
                        is DictationController.Result.Error ->
                            statusView.text = "Error: ${result.message}"
                    }
                }
                loadedEngineId = selected
                statusView.text = "Tap the mic to dictate"
            } catch (e: Exception) {
                Log.e(TAG, "IME engine load failed", e)
                statusView.text = "Engine load failed: ${e.message}"
            } finally {
                isLoadingEngine = false
            }
        }
    }

    /**
     * Commits text into whatever field currently has focus in the host app —
     * this is the actual injection step. [InputMethodService.getCurrentInputConnection]
     * always targets whatever view requested the keyboard, so there is no
     * separate "which app" bookkeeping needed, unlike the AccessibilityService
     * fallback path (Phase 2 item 8) which has to re-find focus itself.
     */
    private fun commitTextToFocusedField(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun lifecycleScope(): CoroutineScope = this.lifecycleScope

    override fun onDestroy() {
        controller?.stopListening()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Murmur/IME"
    }
}
