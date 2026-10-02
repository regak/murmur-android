package ai.pivotstudio.murmur.android.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The floating bubble's visual, matching the Wispr Flow look requested:
 * "when I press it should show like a progress of while I'm talking and
 * when I stop it somehow like goes away" (with a screenshot of Wispr
 * Flow's pulsing purple ring around its mic icon while listening).
 *
 * Three visual states:
 * - IDLE: a small solid circle with the mic glyph, nothing pulsing.
 * - LISTENING: an animated ring around the mic that grows/shrinks in real
 *   time with [setAmplitude] (live mic loudness, 0f-1f, fed from
 *   [ai.pivotstudio.murmur.android.core.AudioCapture]'s onAmplitude
 *   callback via [FloatingBubbleService]) — this is the "progress while
 *   I'm talking" the user asked for; louder speech -> bigger ring, not a
 *   fixed canned animation, so it actually reflects what the mic is
 *   picking up.
 * - Returning to IDLE on [setListening](false): the ring animates back
 *   down to nothing over [SHRINK_DURATION_MS] rather than disappearing
 *   instantly — the "goes away" the user described, done as a smooth
 *   collapse rather than a hard cut.
 *
 * A lightweight custom View (not Compose) since this is hosted inside a
 * raw WindowManager overlay from a Service, not an Activity — Compose's
 * ViewTreeLifecycleOwner/SavedStateRegistry wiring that a ComposeView
 * needs isn't trivially available there without extra plumbing, and this
 * bubble's visual is simple enough that plain Canvas drawing is the more
 * robust choice for this context (same reasoning android-overlay
 * libraries generally use raw Views for).
 */
class DictationWaveBubbleView(context: Context) : View(context) {

    private var isListening = false
    private var ringScale = 0f // 0f..1f, animated

    private var displayedAmplitude = 0f // smoothed, to avoid jittery flicker
    private var targetAmplitude = 0f

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3949AB")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7C4DFF")
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
    private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 46f
    }

    private var shrinkAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    /** Live mic loudness while listening, 0f (silence) to 1f (loud). */
    fun setAmplitude(amplitude: Float) {
        targetAmplitude = amplitude.coerceIn(0f, 1f)
    }

    fun setListening(listening: Boolean) {
        if (isListening == listening) return
        isListening = listening
        shrinkAnimator?.cancel()

        if (listening) {
            ringScale = 1f
            startPulseLoop()
        } else {
            pulseAnimator?.cancel()
            targetAmplitude = 0f
            // Animate the ring collapsing back to nothing -- the "goes
            // away" behavior requested, instead of an instant disappear.
            shrinkAnimator = ValueAnimator.ofFloat(ringScale, 0f).apply {
                duration = SHRINK_DURATION_MS
                addUpdateListener {
                    ringScale = it.animatedValue as Float
                    displayedAmplitude *= 0.85f
                    invalidate()
                }
                start()
            }
        }
        invalidate()
    }

    private fun startPulseLoop() {
        pulseAnimator?.cancel()
        // Drives a steady ~60fps invalidate() while listening so the ring
        // smoothly tracks targetAmplitude (set from the mic's real level)
        // instead of jumping on every audio chunk callback.
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                // Exponential smoothing toward the live target amplitude.
                displayedAmplitude += (targetAmplitude - displayedAmplitude) * 0.3f
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = minOf(width, height) / 2f * 0.62f

        canvas.drawCircle(cx, cy, baseRadius, bubblePaint)

        if (ringScale > 0.01f) {
            // Ring radius grows with live amplitude on top of the base
            // pulse scale -- louder speech visibly expands the ring, matching
            // the "progress while I'm talking" ask rather than a static
            // decoration that ignores what's actually being said.
            val ringRadius = baseRadius + (baseRadius * 0.9f * (0.3f + displayedAmplitude) * ringScale)
            ringPaint.alpha = (255 * ringScale).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        }

        canvas.drawText("\uD83C\uDF99", cx, cy + micPaint.textSize / 3f, micPaint)
    }

    companion object {
        private const val SHRINK_DURATION_MS = 260L
    }
}
