package de.aploi.sussurrobyeyed.inject

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import de.aploi.sussurrobyeyed.ime.SussurroIme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Routes a transcribed line to wherever Sussurro can actually inject it.
 *
 *  1. **IME path** — when SussurroIme is currently selected as the system
 *     input method AND has a live input connection (a text field is
 *     focused and the keyboard is visible), commit through the existing
 *     IME plumbing. This is the smoothest UX: the text appears via the
 *     standard input pipeline, undo / autocorrect / replace behave
 *     normally.
 *  2. **Accessibility path** — when the IME isn't available, fall back to
 *     [SussurroAccessibilityService.appendToFocusedField]. This works
 *     across any focused text field on the device, regardless of the
 *     currently-selected keyboard, but requires the a11y service to be
 *     enabled.
 *
 * Both paths return a boolean so callers can surface a failure (e.g. send
 * a `SessionState.Error` back to the watch with a useful reason).
 */
object TextInjector {

    private const val TAG = "TextInjector"

    /**
     * Try every available path. Returns a [Result] describing what
     * happened, including a short reason string suitable for a watch
     * notification.
     */
    suspend fun inject(context: Context, text: String): Result = withContext(Dispatchers.Main) {
        if (text.isEmpty()) return@withContext Result(false, "empty")

        // 1. IME path — if Sussurro IME is selected and currently bound to
        //    a text field, commit there for the best UX.
        if (isImeSelected(context)) {
            val handled = SussurroIme.tryCommitFromExternal(text)
            if (handled) {
                Log.i(TAG, "inject via IME ok")
                return@withContext Result(true, "ime")
            }
            Log.i(TAG, "IME selected but not bound; falling back to a11y")
        }

        // 2. Accessibility path.
        val a11y = SussurroAccessibilityService.get()
        if (a11y != null) {
            val ok = a11y.appendToFocusedField(text)
            return@withContext if (ok) {
                Result(true, "a11y")
            } else {
                Result(false, "no focus")
            }
        }

        Log.w(TAG, "no injection path available (IME unbound, a11y disabled)")
        Result(false, "enable accessibility")
    }

    /** True when SussurroAccessibilityService is currently enabled in system settings. */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val pkg = context.packageName
        val ours = "$pkg/${SussurroAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(ours, ignoreCase = true) }
    }

    fun isImeSelected(context: Context): Boolean {
        val ours = "${context.packageName}/de.aploi.sussurrobyeyed.ime.SussurroIme"
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )
        return current == ours
    }

    fun isImeEnabled(context: Context): Boolean {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
        val ours = "${context.packageName}/de.aploi.sussurrobyeyed.ime.SussurroIme"
        return imm.enabledInputMethodList.any { it.id == ours }
    }

    data class Result(val ok: Boolean, val reason: String)
}
