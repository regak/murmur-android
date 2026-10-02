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
 * silence -> release -> transcribe -> report result. No text injection yet
 * (Phase 2).
 *
 * Mirrors the macOS `DictationController`'s
 * `starting -> listening -> finishing -> idle` shape, minus the injection step.
 *
 * [segmenter] trims leading/trailing silence from the held-button recording
 * before it reaches the ASR engine (see [SpeechSegmenter] for why — it also
 * means a user who holds the button, pauses, then talks isn't billed encoder
 * time for the pause, and doesn't get silence mis-transcribed as noise).
 *
 * [AudioGain.normalize] runs on the raw buffer BEFORE the VAD/segmenter —
 * this is the fix for words dropped specifically because they were spoken
 * quietly (as opposed to dropped at segment boundaries, which is what
 * [SpeechSegmenter]'s padding/bridging fixes). A quiet word's energy can
 * stay under VAD's detection threshold for its entire duration, meaning
 * VAD never classifies it as speech at all — no amount of padding or
 * bridging after the fact can recover audio that was never flagged as a
 * segment in the first place. Boosting the whole recording's volume
 * toward a consistent target before VAD ever sees it (same fix cloud
 * dictation tools like Wispr Flow use) is what actually addresses that.
 *
 * [onResult] is how the caller (MainActivity) finds out what happened —
 * earlier Phase 1 builds only Log.i'd the transcript, which is invisible
 * on a real device with no way to view Logcat, and looked indistinguishable
 * from "transcription silently did nothing". Every path (success, no
 * speech detected, or an exception from the engine) now reports through
 * this callback so the UI always shows *something*.
 */
class DictationController(
    private val audioCapture: AudioCapture,
    private val engine: TranscriptionEngine,
    private val segmenter: SpeechSegmenter,
    private val onResult: (Result) -> Unit = {},
) {
    enum class State { IDLE, LISTENING, FINISHING }

    sealed class Result {
        data class Transcript(val text: String) : Result()
        object NoSpeechDetected : Result()
        data class Error(val message: String) : Result()
    }

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
                    val normalized = AudioGain.normalize(raw)
                    segmenter.accept(normalized)
                    segmenter.extractSpeech()
                }
            }

            if (speechOnly.isEmpty()) {
                Log.i(TAG, "No speech detected (silence or button tap too short)")
                onResult(Result.NoSpeechDetected)
            } else {
                Log.i(TAG, "VAD kept ${speechOnly.size}/${raw.size} samples")
                try {
                    val text = engine.transcribe(speechOnly)
                    Log.i(TAG, "Transcript: \"$text\"")
                    onResult(
                        if (text.isBlank()) Result.NoSpeechDetected else Result.Transcript(text),
                    )
                    // Phase 2 TODO: TextFormatter -> TextInjector here.
                } catch (e: Exception) {
                    Log.e(TAG, "Transcription failed", e)
                    onResult(Result.Error(e.message ?: e.toString()))
                }
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
