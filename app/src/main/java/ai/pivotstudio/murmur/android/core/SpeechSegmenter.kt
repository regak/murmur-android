package ai.pivotstudio.murmur.android.core

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import android.content.Context

/**
 * Wraps sherpa-onnx's Silero VAD so [DictationController] doesn't just send
 * everything between press and release to the ASR engine verbatim.
 *
 * Phase 1 use: trims leading/trailing silence from the held-button segment
 * before it reaches Moonshine — a user who holds the button, pauses half a
 * second before speaking, then pauses again before releasing, shouldn't pay
 * for that silence in encoder time or get it mis-transcribed as noise.
 *
 * This also lays the groundwork for a later "tap to start, auto-stop on
 * silence" trigger mode (Phase 3 settings), which needs exactly this speech/
 * silence segmentation rather than a manual release.
 * Model file is NOT bundled in the APK — [ModelDownloader] fetches it on
 * first launch alongside the Moonshine ASR files. See that class and
 * MainActivity's download-gate for details.
 *
 * Dropped-word fixes (two distinct failure modes, both from real-device use):
 *
 * 1. Edge clipping: Silero VAD only flags a window as "speech" after enough
 *    energy has accumulated across it, so segment boundaries consistently
 *    start a bit LATE and end a bit EARLY — clipping the first phoneme of
 *    the first word and the last consonant of the last word. Fixed by
 *    padding [PAD_SAMPLES] of real audio around each detected segment.
 *
 * 2. Mid-sentence dropouts on quiet speech: when volume drops mid-sentence
 *    (a word spoken softly), the VAD can lose the signal for that word
 *    entirely and read it as a silence gap — splitting one sentence into
 *    two separate detected segments with a hole where the quiet word was.
 *    Padding the edges of each segment doesn't help here because that word
 *    was never classified as speech at all, so there's no segment to pad.
 *    Fixed by [GAP_BRIDGE_SAMPLES]: if two detected segments are close
 *    together (a plausible quiet-word gap, not a real pause), the raw
 *    audio IN the gap is included too rather than discarded, on the
 *    assumption that genuine pauses between distinct thoughts are usually
 *    longer than one swallowed word.
 */
class SpeechSegmenter(context: Context) {

    private val vad: Vad = Vad(
        assetManager = null,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "${ModelDownloader(context).vadDir.absolutePath}/silero_vad.onnx",
                threshold = 0.25f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.1f,
                windowSize = WINDOW_SIZE_SAMPLES,
                maxSpeechDuration = 30.0f,
            ),
            sampleRate = SAMPLE_RATE_HZ,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    private var rawBuffer: ShortArray = ShortArray(0)

    /**
     * Feed the full held-button recording in. VAD windows are fixed-size
     * ([WINDOW_SIZE_SAMPLES]); the final partial window (if any) is padded
     * with silence so no trailing speech is dropped.
     */
    fun accept(pcm16kMono: ShortArray) {
        rawBuffer = pcm16kMono
        var offset = 0
        while (offset < pcm16kMono.size) {
            val end = minOf(offset + WINDOW_SIZE_SAMPLES, pcm16kMono.size)
            val window = FloatArray(WINDOW_SIZE_SAMPLES)
            for (i in offset until end) {
                window[i - offset] = pcm16kMono[i] / 32768.0f
            }
            vad.acceptWaveform(window)
            offset = end
        }
        vad.flush()
    }

    /**
     * Returns the speech portion only, as one concatenated segment, trimming
     * long silences at the edges and between distinct utterances — but
     * padded and gap-bridged (see class doc) so the VAD's typical
     * late-start/early-end clipping and quiet-word dropouts don't eat real
     * words. Returns an empty array if VAD found no speech (e.g. the user
     * held the button but said nothing).
     */
    fun extractSpeech(): ShortArray {
        // Collect raw (start, end) sample-index ranges from the VAD first —
        // pulling the actual audio from rawBuffer instead of segment.samples
        // so padding can reach outside what the VAD itself returned.
        val ranges = ArrayList<IntRange>()
        while (!vad.empty()) {
            val segment = vad.front()
            val start = (segment.start - PAD_SAMPLES).coerceAtLeast(0)
            val end = (segment.start + segment.samples.size + PAD_SAMPLES).coerceAtMost(rawBuffer.size)
            if (start < end) ranges.add(start until end)
            vad.pop()
        }
        if (ranges.isEmpty()) return ShortArray(0)

        // Merge ranges that overlap OR are close enough to plausibly be a
        // quiet word the VAD missed rather than a genuine pause (gap-bridge,
        // see class doc point 2) — both cases collapse into one range so we
        // don't duplicate or drop audio in between.
        ranges.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        var current = ranges[0]
        for (next in ranges.drop(1)) {
            current = if (next.first - current.last <= GAP_BRIDGE_SAMPLES) {
                current.first until maxOf(current.last + 1, next.last + 1)
            } else {
                merged.add(current)
                next
            }
        }
        merged.add(current)

        val result = ArrayList<Short>()
        for (range in merged) {
            for (i in range) result.add(rawBuffer[i])
        }
        return result.toShortArray()
    }

    fun reset() {
        vad.reset()
    }

    fun close() {
        vad.release()
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val WINDOW_SIZE_SAMPLES = 512

        /**
         * ~240ms of real audio padding on each side of a detected segment.
         * Chosen to comfortably cover VAD's detection lag (windowSize=512
         * samples = 32ms per inference step, plus the energy ramp-up Silero
         * needs) without padding in enough silence to bring back the
         * encoder-time/mis-transcription cost this class exists to avoid.
         */
        const val PAD_SAMPLES = (SAMPLE_RATE_HZ * 0.24).toInt()

        /**
         * Gap (in samples) below which two detected segments get bridged
         * (the raw audio between them kept, not discarded) instead of
         * treated as two separate utterances. ~700ms — long enough to
         * cover one swallowed quiet word plus the silence-duration delay
         * (minSilenceDuration=0.5s above) before VAD reports the gap at
         * all, short enough that a genuine pause between separate sentences
         * still gets trimmed as silence.
         */
        const val GAP_BRIDGE_SAMPLES = (SAMPLE_RATE_HZ * 0.7).toInt()
    }
}
