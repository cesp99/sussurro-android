package de.aploi.sussurrobyeyed.inject

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.MainThread

/**
 * Accessibility service that lets Sussurro write transcribed text into any
 * focused text field, even when Sussurro's IME isn't selected.
 *
 * Strategy: walk up from the most recent input-focused node, fall back to
 * the active window's input-focus root, and use [AccessibilityNodeInfo.ACTION_SET_TEXT]
 * with the existing text appended. We deliberately avoid clobbering the
 * user's text — we always append, the same way an IME `commitText(...)`
 * would.
 *
 * The user has to explicitly enable the service in system settings. There
 * is no way around that (Android's intentional design).
 */
class SussurroAccessibilityService : AccessibilityService() {

    private var lastFocusedNode: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "accessibility service connected")
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        runCatching { lastFocusedNode?.recycle() }
        lastFocusedNode = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val src = event.source ?: return
                if (src.isEditable) {
                    runCatching { lastFocusedNode?.recycle() }
                    lastFocusedNode = src
                } else {
                    src.recycle()
                }
            }
        }
    }

    override fun onInterrupt() {
        // No-op.
    }

    /**
     * Append [text] to whatever editable node currently holds input focus.
     *
     * @return true if the text was committed successfully.
     */
    @MainThread
    fun appendToFocusedField(text: String): Boolean {
        if (text.isEmpty()) return false
        val node = findEditableFocus() ?: run {
            Log.w(TAG, "appendToFocusedField: no editable focus")
            return false
        }
        return try {
            val existing = node.text?.toString() ?: ""
            val updated = existing + text
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated,
                )
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                // Also try to move the cursor to end. Best effort; some
                // fields don't honour selection actions.
                val cursorArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, updated.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, updated.length)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
            }
            Log.i(TAG, "appendToFocusedField: ok=$ok len=${text.length}")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "appendToFocusedField failed", t)
            false
        } finally {
            node.refresh()
        }
    }

    /**
     * Resolve the current editable focus. Prefers the active window's input
     * focus over the cached [lastFocusedNode], but falls back to it when
     * needed (e.g. when no node currently holds input focus but the user
     * just blurred a field that they want us to dictate into).
     */
    private fun findEditableFocus(): AccessibilityNodeInfo? {
        val live = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (live != null && live.isEditable) return live
        live?.recycle()

        val cached = lastFocusedNode ?: return null
        return if (cached.refresh() && cached.isEditable) cached else null
    }

    companion object {
        private const val TAG = "SussurroA11y"

        @Volatile
        private var instance: SussurroAccessibilityService? = null

        /** Lookup the currently-bound service, or null if it isn't enabled. */
        fun get(): SussurroAccessibilityService? = instance
    }
}
