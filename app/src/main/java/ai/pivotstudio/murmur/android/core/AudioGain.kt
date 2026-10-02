package ai.pivotstudio.murmur.android.core

import kotlin.math.abs
import kotlin.math.min

/**
 * Automatic gain control (AGC): boosts quiet recordings up to a consistent
 * target loudness before VAD or the ASR engine ever see the audio.
 *
 * Why this exists: the earlier two dropped-word fixes (edge-padding and
 * gap-bridging in [SpeechSegmenter]) both worked AFTER voice-activity
 * detection had already run, patching up segment boundaries. Neither can
 * fix speech that VAD never classified as speech in the first place —
 * which is exactly what happens when someone talks quietly: the signal
 * energy stays under the VAD's detection threshold for the entire word,
 * so there's no segment boundary to pad or bridge, the audio is simply
 * never flagged as speech at all.
 *
 * This is also the documented reason cloud dictation tools (Wispr Flow
 * included) don't have this problem as badly: gain-normalizing audio
 * BEFORE voice detection is the standard fix, not a bigger/better VAD
 * model or a smarter threshold. Lowering the VAD threshold (tried in the
 * previous two fixes) only gets you so far before it starts triggering on
 * background noise instead — normalizing the signal itself, once, up
 * front, is the fix that scales.
 *
 * Applied once per held-button recording, on the full raw buffer, before
 * [SpeechSegmenter.accept] — not per-chunk during capture, so the gain
 * decision is based on the loudest moment of the whole utterance rather
 * than fluctuating window to window (which would pump up background hiss
 * during a pause right after a loud word).
 */
object AudioGain {

    /**
     * Returns a new array scaled toward [TARGET_PEAK_FRACTION] of full
     * scale, based on the loudest sample in [pcm16kMono]. Gain is capped at
     * [MAX_GAIN] so near-silent recordings (e.g. button pressed but nothing
     * said, or pure background noise) don't get amplified into audible
     * garbage that the VAD or ASR then mistakes for speech — the fix is for
     * genuinely-quiet SPEECH, not for turning noise into something audible.
     */
    fun normalize(pcm16kMono: ShortArray): ShortArray {
        if (pcm16kMono.isEmpty()) return pcm16kMono

        var peak = 0
        for (s in pcm16kMono) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        if (peak == 0) return pcm16kMono // true digital silence — nothing to boost

        val targetPeak = (Short.MAX_VALUE * TARGET_PEAK_FRACTION).toInt()
        val gain = min(targetPeak.toFloat() / peak.toFloat(), MAX_GAIN)
        if (gain <= 1.0f) return pcm16kMono // already loud enough; never attenuate

        return ShortArray(pcm16kMono.size) { i ->
            (pcm16kMono[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private const val TARGET_PEAK_FRACTION = 0.75f

    /**
     * Caps boosting at 6x (~15.6dB). High enough to rescue a genuinely
     * quiet talker, low enough that pressing the button and saying nothing
     * (pure mic noise floor) doesn't get amplified into something VAD
     * mistakes for speech.
     */
    private const val MAX_GAIN = 6.0f
}
