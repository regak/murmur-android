package ai.pivotstudio.murmur.android.core

import ai.pivotstudio.murmur.android.asr.TranscriptionEngine
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch

/**
 * Phase 1 state machine: press-and-hold -> accumulate audio -> release ->
 * transcribe -> log. No text injection yet (that's Phase 2).
 *
 * Mirrors the macOS `DictationController`'s
 * `starting -> listening -> finishing -> idle` shape, minus the injection step.
 */
class DictationController(
    private val audioCapture: AudioCapture,
    private val engine: TranscriptionEngine,
) {
    enum class State { IDLE, LISTENING, FINISHING }

    var state: State = State.IDLE
        private set

    /** Called when the user presses the overlay bubble / holds the trigger. */
    fun startListening(scope: CoroutineScope) {
        check(state == State.IDLE) { "startListening() called while state=$state" }
        state = State.LISTENING

        val chunks = audioCapture.start(scope)
        val collected = ArrayList<Short>()

        scope.launch {
            chunks.consumeEach { chunk -> collected.addAll(chunk.toList()) }
            // Channel closes when stop() is called (release) — then transcribe.
            state = State.FINISHING
            val pcm = collected.toShortArray()
            if (pcm.isEmpty()) {
                Log.i(TAG, "Silence — nothing to transcribe")
            } else {
                val text = engine.transcribe(pcm)
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
