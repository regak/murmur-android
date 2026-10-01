package ai.pivotstudio.murmur.android.core

import ai.pivotstudio.murmur.android.asr.TranscriptionEngine
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Phase 1 state machine: press-and-hold -> accumulate audio -> VAD trims
 * silence -> release -> transcribe -> log. No text injection yet (Phase 2).
 *
 * Mirrors the macOS `DictationController`'s
 * `starting -> listening -> finishing -> idle` shape, minus the injection step.
 *
 * [segmenter] trims leading/trailing silence from the held-button recording
 * before it reaches the ASR engine (see [SpeechSegmenter] for why — it also
 * means a user who holds the button, pauses, then talks isn't billed encoder
 * time for the pause, and doesn't get silence mis-transcribed as noise).
 */
class DictationController(
    private val audioCapture: AudioCapture,
    private val engine: TranscriptionEngine,
    private val segmenter: SpeechSegmenter,
) {
    enum class State { IDLE, LISTENING, FINISHING }

    var state: State = State.IDLE
        private set

    /** Called when the user presses the overlay bubble / holds the trigger. */
    fun startListening(scope: CoroutineScope) {
        check(state == State.IDLE) { "startListening() called while state=$state" }
        state = State.LISTENING
        segmenter.reset()

        val chunks = audioCapture.start(scope)
        val collected = ArrayList<Short>()

        scope.launch {
            chunks.consumeEach { chunk -> collected.addAll(chunk.toList()) }
            // Channel closes when stop() is called (release) — then transcribe.
            state = State.FINISHING

            val raw = collected.toShortArray()
            val speechOnly = withContext(Dispatchers.Default) {
                if (raw.isEmpty()) raw else {
                    segmenter.accept(raw)
                    segmenter.extractSpeech()
                }
            }

            if (speechOnly.isEmpty()) {
                Log.i(TAG, "No speech detected (silence or button tap too short)")
            } else {
                Log.i(TAG, "VAD kept ${speechOnly.size}/${raw.size} samples")
                val text = engine.transcribe(speechOnly)
                Log.i(TAG, "Transcript: \"$text\"")
                // Phase 2 TODO: TextFormatter -> TextInjector here.
            }
            state = State.IDLE
        }
    }

    /** Called when the user releases the overlay bubble / trigger. */
    fun stopListening() {
        audioCapture.stop()
    }

    companion object {
        private const val TAG = "Murmur/Dictation"
    }
}
