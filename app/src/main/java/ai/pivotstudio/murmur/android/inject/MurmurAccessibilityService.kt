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
     * focused for input, system-wide (any app). Returns true if a focused
     * editable node was found and the insert succeeded.
     *
     * Appends rather than replaces: finds the focused node's existing text
     * and cursor/selection position, and splices [text] in at that point —
     * matches how a real keyboard's commitText behaves (insert, not
     * overwrite), so dictating into a field that already has text in it
     * (e.g. continuing a WhatsApp message) doesn't destroy what's there.
     */
    fun insertTextAtCursor(text: String): Boolean {
        val node = findFocusedEditableNode() ?: return false
        return try {
            val existing = node.text?.toString() ?: ""
            val selStart = if (node.textSelectionStart >= 0) node.textSelectionStart else existing.length
            val selEnd = if (node.textSelectionEnd >= 0) node.textSelectionEnd else existing.length
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
            setOk
        } finally {
            node.recycle()
        }
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
