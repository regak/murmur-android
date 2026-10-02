package ai.pivotstudio.murmur.android.inject

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phase 2 item 8: the fallback/general-purpose text injector, used by the
 * floating bubble ([ai.pivotstudio.murmur.android.overlay.FloatingBubbleService])
 * since a floating overlay (unlike [ai.pivotstudio.murmur.android.ime.MurmurInputMethodService])
 * has no InputConnection of its own — it isn't the keyboard, so it cannot
 * call `commitText` directly. This service finds whatever node is focused
 * system-wide (any app) and sets its text directly via the Accessibility
 * API, which is exactly the mechanism Wispr Flow's floating bubble uses on
 * Android (confirmed via public teardown write-ups — they ship an
 * AccessibilityService alongside their floating mic button for the same
 * "works while some other keyboard/app owns the field" reason).
 *
 * Requires the user to manually enable this service once under Settings ->
 * Accessibility -> Murmur (Android does not allow enabling Accessibility
 * services programmatically, by design, to prevent abuse) — see
 * [ai.pivotstudio.murmur.android.ui.MainActivity]'s permission-onboarding
 * row for the settings deep link.
 */
class MurmurAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event handling needed — this service is used purely as an
        // on-demand text-injection API via [instance], not for passive
        // event monitoring. An empty implementation is required to
        // satisfy the base class / fulfil the service contract.
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    /**
     * Inserts [text] at the current cursor position in whichever node is
     * focused for input, system-wide (any app).
     *
     * [ACTION_PASTE] is tried FIRST (not as a fallback): it inserts at the
     * cursor the way a real paste gesture does, without ever reading or
     * rewriting the field's full text — so it can't be fooled by a field's
     * greyed-out placeholder (e.g. WhatsApp's "Message") the way
     * [trySetText] can. Confirmed via two rounds of real-device testing:
     * ACTION_SET_TEXT alone produced "MessageHi there." because it read the
     * placeholder as real existing text; detecting the placeholder via
     * isShowingHintText and even a hintText-string-match both still missed
     * it on WhatsApp specifically (the flag/attribute apparently isn't set
     * reliably there). Paste sidesteps the detection problem entirely by
     * never needing to know what the "existing text" is in the first place.
     * [trySetText] (ACTION_SET_TEXT) is kept only as a last-resort fallback
     * for apps/fields that don't support ACTION_PASTE via Accessibility.
     *
     * Returns true if a focused editable node was found and either path
     * succeeded.
     */
    fun insertTextAtCursor(text: String): Boolean {
        val node = findFocusedEditableNode() ?: return false
        return try {
            if (tryPaste(node, text)) return true
            trySetText(node, text)
        } finally {
            node.recycle()
        }
    }

    /**
     * Appends rather than replaces: finds the focused node's existing text
     * and cursor/selection position, and splices [text] in at that point —
     * matches how a real keyboard's commitText behaves (insert, not
     * overwrite), so dictating into a field that already has text in it
     * (e.g. continuing a WhatsApp message) doesn't destroy what's there.
     */
    private fun trySetText(node: AccessibilityNodeInfo, text: String): Boolean {
        // node.text reports the greyed-out placeholder/hint (e.g. WhatsApp's
        // "Message") as if it were real existing content when the field is
        // empty -- a known Accessibility-API quirk (hint text is exposed via
        // getText() so screen readers can announce it). The first fix relied
        // on isShowingHintText alone, but that flag isn't reliably set by
        // every app (confirmed on WhatsApp via real-device report: "it
        // still show the Message in the beginning" after that fix shipped).
        // More robust: also compare the node's actual text directly against
        // its declared hintText (getHintText(), API 26+) -- if they match,
        // it's definitely placeholder content regardless of whether the app
        // bothered to set isShowingHintText correctly.
        val rawText = node.text?.toString()
        val hintText = if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString() else null
        val isHint = node.isShowingHintText || (hintText != null && rawText == hintText)
        val existing = if (isHint) "" else (rawText ?: "")
        val selStart = if (!isHint && node.textSelectionStart >= 0) node.textSelectionStart else existing.length
        val selEnd = if (!isHint && node.textSelectionEnd >= 0) node.textSelectionEnd else existing.length
        val newText = existing.substring(0, selStart) + text + existing.substring(selEnd)
        val newCursor = selStart + text.length

        val arguments = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText,
            )
        }
        val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (setOk) {
            val selectionArgs = android.os.Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        }
        return setOk
    }

    /**
     * Clipboard + ACTION_PASTE fallback — see [insertTextAtCursor] doc.
     * Restores whatever was previously on the clipboard afterward, since
     * silently overwriting the user's actual clipboard contents would be
     * a surprising side effect of just dictating.
     */
    private fun tryPaste(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val previousClip = clipboard.primaryClip
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Murmur dictation", text))
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (previousClip != null) {
            // Restore slightly later — replacing it immediately can race
            // with the paste action actually reading the clipboard.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { clipboard.setPrimaryClip(previousClip) }
            }, 500)
        }
        return pasted
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    companion object {
        /**
         * Set by the running service instance once the user has enabled it
         * in Settings; null means "not enabled" / "not yet connected" —
         * callers (the floating bubble) must null-check before use and
         * fall back to clipboard-only if this is null.
         */
        var instance: MurmurAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}
