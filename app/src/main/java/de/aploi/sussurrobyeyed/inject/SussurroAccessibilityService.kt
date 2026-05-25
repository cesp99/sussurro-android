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
     * Insert [text] at the current cursor position of whatever editable node
     * has input focus. Falls back to "append at end" when the node doesn't
     * report a usable selection.
     *
     * Note about hint text: a freshly-focused empty field still returns its
     * placeholder string from [AccessibilityNodeInfo.getText] — e.g.
     * WhatsApp's compose box returns "Messaggio" in Italian even though the
     * buffer is empty. We must NOT treat that as existing user content;
     * concatenating onto it produces transcripts like "Messaggiociao". The
     * authoritative API for this is [AccessibilityNodeInfo.isShowingHintText]
     * (added in API 26, well below our minSdk 31), which we check before
     * reading [AccessibilityNodeInfo.getText].
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
            // Treat the field as empty whenever the node is currently
            // displaying its hint/placeholder string rather than real user
            // content. This is the canonical way Android distinguishes
            // "user has typed something" from "we're rendering the
            // placeholder" — `getText()` alone returns the hint and would
            // otherwise be concatenated onto the transcript.
            val existing = if (node.isShowingHintText) {
                ""
            } else {
                node.text?.toString() ?: ""
            }

            // Splice [text] in at the caret, the way an IME's commitText
            // would. When the node doesn't report a valid selection
            // (selectionStart < 0) treat it as an append at the end of the
            // buffer.
            val selStart = node.textSelectionStart
            val selEnd = node.textSelectionEnd
            val (insertAt, replaceUpTo) = if (selStart in 0..existing.length &&
                selEnd in selStart..existing.length
            ) {
                selStart to selEnd
            } else {
                existing.length to existing.length
            }
            val updated = buildString(existing.length + text.length) {
                append(existing, 0, insertAt)
                append(text)
                append(existing, replaceUpTo, existing.length)
            }
            val newCursor = insertAt + text.length

            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated,
                )
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                // Best effort: place the cursor right after the inserted
                // run so the user can keep dictating / typing. Some fields
                // don't honour selection actions; those just keep whatever
                // selection they had.
                val cursorArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
            }
            Log.i(
                TAG,
                "appendToFocusedField: ok=$ok len=${text.length} " +
                    "hint=${node.isShowingHintText} " +
                    "sel=$selStart..$selEnd existingLen=${existing.length}",
            )
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
