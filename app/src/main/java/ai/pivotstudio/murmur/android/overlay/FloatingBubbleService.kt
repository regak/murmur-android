package ai.pivotstudio.murmur.android.overlay

import ai.pivotstudio.murmur.android.asr.EnginePreferences
import ai.pivotstudio.murmur.android.asr.createEngine
import ai.pivotstudio.murmur.android.core.AudioCapture
import ai.pivotstudio.murmur.android.core.DictationController
import ai.pivotstudio.murmur.android.core.ModelDownloader
import ai.pivotstudio.murmur.android.core.SpeechSegmenter
import ai.pivotstudio.murmur.android.inject.MurmurAccessibilityService
import ai.pivotstudio.murmur.android.ui.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * The "hanging button" feature: a small draggable circular button that
 * floats on top of every other app (Wispr Flow calls it a floating mic
 * button; this mirrors that exact interaction — press and hold anywhere
 * the bubble currently sits, talk, release). Requested directly: "Can you
 * develop a similar button to the one in Wispr Flow, the hanging button,
 * so I can click and hold" — with a screenshot showing it floating over
 * WhatsApp's own keyboard, i.e. active system-wide, not just inside Murmur.
 *
 * Why this needs a foreground [Service] (not just a View from an Activity):
 * the bubble must keep running and stay drawn while the user is in a
 * DIFFERENT app (WhatsApp in the screenshot) — an Activity's window dies
 * the moment the user leaves it. `TYPE_APPLICATION_OVERLAY` + a foreground
 * service with a persistent notification is the only way Android allows
 * a view (and, critically, live mic access — Android aggressively kills
 * background mic access without FOREGROUND_SERVICE_MICROPHONE) to survive
 * across app switches. Requires the user to grant "Display over other
 * apps" once (SYSTEM_ALERT_WINDOW; already declared in the manifest).
 *
 * Injection path: unlike [ai.pivotstudio.murmur.android.ime.MurmurInputMethodService],
 * this service is NOT the keyboard, so it has no InputConnection to commit
 * text through. It uses [MurmurAccessibilityService] instead (same approach
 * Wispr Flow's own floating button uses on Android) to set text directly
 * into whichever field is focused system-wide. If the user hasn't enabled
 * that Accessibility service yet, falls back to copying the transcript to
 * the clipboard and telling them to paste — never silently drops a result.
 *
 * Draggable: the bubble can be repositioned by dragging; a tap-vs-drag
 * threshold ([DRAG_THRESHOLD_PX]) distinguishes "the user is moving the
 * bubble" from "the user pressed to start dictating", exactly like Wispr
 * Flow's own bubble (and most floating-button overlays) behave.
 */
class FloatingBubbleService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle get() = lifecycleRegistry
    private val serviceScope: LifecycleCoroutineScope get() = lifecycleScope

    private lateinit var windowManager: WindowManager
    private var bubbleView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var controller: DictationController? = null
    private var isEngineReady = false

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
        addBubble()
        loadEngine()
    }

    private fun startForegroundWithNotification() {
        val channelId = "murmur_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Murmur floating mic",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Murmur mic is floating")
            .setContentText("Hold the bubble anywhere on screen to dictate. Tap to stop.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun addBubble() {
        val bubble = TextView(this).apply {
            text = "\uD83C\uDF99"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF3949AB"))
            }
        }

        val params = WindowManager.LayoutParams(
            BUBBLE_SIZE_PX,
            BUBBLE_SIZE_PX,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        var downRawX = 0f
        var downRawY = 0f
        var downParamX = 0
        var downParamY = 0
        var isDragging = false
        var isHeld = false

        bubble.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = params.x
                    downParamY = params.y
                    isDragging = false
                    // Start recording immediately on press, not on a later
                    // ACTION_MOVE. A steady hold with zero finger movement
                    // never generates a MOVE event at all, so gating the
                    // start on MOVE meant holding still (the normal way to
                    // press a button) silently recorded nothing for the
                    // whole hold -- recording only began, for an instant,
                    // on release. Real-device bug report: "when clicked it
                    // does not type... pasted it just O" (a single garbage
                    // character from that near-zero-length capture).
                    isHeld = true
                    startDictating(bubble)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
                        isDragging = true
                        if (isHeld) {
                            // Movement past the threshold means this is a drag,
                            // not a hold-to-talk -- cancel the recording that
                            // started on ACTION_DOWN rather than transcribing
                            // whatever was captured during the drag gesture.
                            stopDictating()
                            isHeld = false
                        }
                    }
                    if (isDragging) {
                        params.x = downParamX + dx.toInt()
                        params.y = downParamY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isHeld) {
                        stopDictating()
                        isHeld = false
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, params)
        bubbleView = bubble
        layoutParams = params
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun loadEngine() {
        serviceScope.launch {
            val selected = EnginePreferences(this@FloatingBubbleService).selected
            val downloader = ModelDownloader(this@FloatingBubbleService)
            if (!downloader.isComplete(selected)) {
                // The floating bubble assumes the model was already downloaded
                // via the main app or the IME — a silent multi-hundred-MB
                // download with no UI to show progress on would be a bad
                // surprise. Bail out with a clear signal instead.
                bubbleView?.text = "\u26A0"
                Log.w(TAG, "Model ${selected.name} not downloaded yet; open the Murmur app first")
                return@launch
            }
            try {
                val audioCapture = AudioCapture(this@FloatingBubbleService)
                val engine = selected.createEngine(this@FloatingBubbleService)
                val segmenter = SpeechSegmenter(this@FloatingBubbleService)
                engine.load()
                controller = DictationController(audioCapture, engine, segmenter) { result ->
                    when (result) {
                        is DictationController.Result.Transcript -> deliverText(result.text)
                        is DictationController.Result.NoSpeechDetected -> flashBubble("\uD83C\uDF99")
                        is DictationController.Result.Error -> {
                            Log.e(TAG, "Bubble transcription error: ${result.message}")
                            flashBubble("\u26A0")
                        }
                    }
                }
                isEngineReady = true
            } catch (e: Exception) {
                Log.e(TAG, "Bubble engine load failed", e)
                bubbleView?.text = "\u26A0"
            }
        }
    }

    private fun startDictating(bubble: TextView) {
        if (!isEngineReady) return
        bubble.text = "\u25CF" // solid dot: recording indicator
        controller?.startListening(serviceScope)
    }

    private fun stopDictating() {
        bubbleView?.text = "\uD83C\uDF99"
        controller?.stopListening()
    }

    /**
     * Delivers a finished transcript to whatever's focused, system-wide.
     * Prefers direct Accessibility-API injection (no visible UI, text just
     * appears, same feel as the IME path); falls back to clipboard + a
     * toast telling the user to paste if the Accessibility service hasn't
     * been enabled yet — a transcript is never silently lost either way.
     */
    private fun deliverText(text: String) {
        val service = MurmurAccessibilityService.instance
        val injected = service?.insertTextAtCursor("$text ") ?: false
        if (injected) {
            flashBubble("\u2713")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Murmur transcript", text))
        android.widget.Toast.makeText(
            this,
            "Copied to clipboard (enable Accessibility for direct typing): \"$text\"",
            android.widget.Toast.LENGTH_LONG,
        ).show()
        flashBubble("\uD83D\uDCCB")
    }

    private fun flashBubble(symbol: String) {
        bubbleView?.text = symbol
        bubbleView?.postDelayed({ bubbleView?.text = "\uD83C\uDF99" }, 900)
    }

    override fun onDestroy() {
        controller?.stopListening()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "Murmur/Bubble"
        private const val NOTIFICATION_ID = 1001
        private const val BUBBLE_SIZE_PX = 150
        private const val DRAG_THRESHOLD_PX = 20
        const val ACTION_STOP = "ai.pivotstudio.murmur.android.overlay.STOP"
    }
}
