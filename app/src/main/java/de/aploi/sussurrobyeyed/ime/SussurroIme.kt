package de.aploi.sussurrobyeyed.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import de.aploi.sussurrobyeyed.BuildConfig
import de.aploi.sussurrobyeyed.audio.AudioRecorder
import de.aploi.sussurrobyeyed.data.Settings
import de.aploi.sussurrobyeyed.data.SettingsStore
import de.aploi.sussurrobyeyed.model.ModelDownloader
import de.aploi.sussurrobyeyed.ui.components.CapsuleState
import de.aploi.sussurrobyeyed.whisper.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Sussurro's voice-only keyboard.
 *
 * The IME surface is a single Compose tree rendering [SussurroCapsule]. Tapping
 * the capsule toggles between listening and transcribing; the transcript is
 * committed into the host text field via [getCurrentInputConnection].
 *
 * The service implements [LifecycleOwner], [ViewModelStoreOwner] and
 * [SavedStateRegistryOwner] because [ComposeView] requires these owners to be
 * present on its view tree, and an [InputMethodService] is not a
 * [androidx.activity.ComponentActivity].
 */
class SussurroIme : InputMethodService(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // ---- App resources ----
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder = AudioRecorder()
    private lateinit var settingsStore: SettingsStore
    private lateinit var downloader: ModelDownloader

    private var engine: WhisperEngine? = null
    private var transcribeJob: Job? = null

    // ---- UI state ----
    private val _capsuleState = MutableStateFlow(CapsuleState.Idle)
    val capsuleState: StateFlow<CapsuleState> = _capsuleState.asStateFlow()

    private lateinit var settingsFlow: StateFlow<Settings>

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        // Compose's window recomposer requires at least STARTED to actually
        // produce frames. Pre-warm to STARTED here; we'll bump to RESUMED in
        // onStartInputView when the IME is actually visible.
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        // Wire the view-tree owners onto the IME's window decor view. Without
        // this, `findViewTreeLifecycleOwner` walking up from any child crashes
        // with "ViewTreeLifecycleOwner not found from … parentPanel".
        attachOwnersToWindowDecor()

        settingsStore = SettingsStore(applicationContext)
        downloader = ModelDownloader(applicationContext)

        settingsFlow = settingsStore.settings.stateIn(
            scope = serviceScope,
            started = SharingStarted.Eagerly,
            initialValue = Settings(),
        )

        if (BuildConfig.DEBUG) {
            // Write the most recent capture into the app's files dir so we can
            // adb-pull it and verify what whisper.cpp is actually being fed.
            val dir = java.io.File(applicationContext.filesDir, "debug")
            dir.mkdirs()
            recorder.debugWavDir = dir
        }
    }

    override fun onCreateInputView(): View {
        // Safety: re-apply in case the decor view was recreated.
        attachOwnersToWindowDecor()

        val composeView = ComposeView(this).apply {
            // Belt-and-braces — anything that queries the ComposeView itself
            // (instead of walking up to the decor view) still gets owners.
            setViewTreeLifecycleOwner(this@SussurroIme)
            setViewTreeViewModelStoreOwner(this@SussurroIme)
            setViewTreeSavedStateRegistryOwner(this@SussurroIme)
            setContent {
                KeyboardSurface(
                    capsuleStateFlow = capsuleState,
                    rmsFlow = recorder.rms,
                    settingsFlow = settingsFlow,
                    onCapsuleTap = ::onCapsuleTap,
                )
            }
        }
        return composeView
    }

    private fun attachOwnersToWindowDecor() {
        val decor = window?.window?.decorView ?: return
        decor.setViewTreeLifecycleOwner(this)
        decor.setViewTreeViewModelStoreOwner(this)
        decor.setViewTreeSavedStateRegistryOwner(this)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        // Reset the capsule on every focus so we don't carry over a stale state
        // when the user switches between text fields.
        _capsuleState.value = if (!hasMicPermission()) {
            CapsuleState.Idle // permission prompt handled inside KeyboardSurface label
        } else if (!downloader.isInstalled()) {
            CapsuleState.Idle
        } else {
            CapsuleState.Idle
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Cancel any in-flight recording so we don't keep the mic hot after the
        // user has dismissed the keyboard.
        if (recorder.state.value == AudioRecorder.State.Recording) {
            recorder.cancel()
        }
        transcribeJob?.cancel()
        transcribeJob = null
        _capsuleState.value = CapsuleState.Idle
        // Guard against being called from super.onDestroy() after we've already
        // moved to DESTROYED — the framework calls onFinishInputView as part of
        // service teardown and LifecycleRegistry refuses backwards transitions.
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }
    }

    override fun onDestroy() {
        // Cancel work first while the lifecycle is still valid.
        recorder.cancel()
        transcribeJob?.cancel()
        // Release whisper.cpp synchronously: serviceScope is about to be cancelled.
        engine?.let { e ->
            try {
                runBlocking { e.release() }
            } catch (t: Throwable) {
                Log.w(TAG, "engine release failed", t)
            }
            engine = null
        }
        serviceScope.cancel()
        // Let the framework finish its teardown (which calls onFinishInputView)
        // before we mark the lifecycle DESTROYED; otherwise the back-transition
        // crashes.
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    /**
     * Called by [KeyboardSurface] when the user taps the capsule.
     *
     * The state machine:
     *   Idle -> Listening  : start recording
     *   Listening -> Transcribing : stop recording, kick off whisper.cpp
     *   Transcribing : ignore (we'll auto-return to Idle when done)
     */
    private fun onCapsuleTap() {
        if (!hasMicPermission()) {
            openOnboarding()
            return
        }
        if (!downloader.isInstalled()) {
            openOnboarding()
            return
        }

        when (_capsuleState.value) {
            CapsuleState.Idle -> startListening()
            CapsuleState.Listening -> stopAndTranscribe()
            CapsuleState.Transcribing -> { /* wait it out */ }
        }
    }

    private fun startListening() {
        try {
            recorder.start(serviceScope)
            _capsuleState.value = CapsuleState.Listening
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start recorder", t)
            toast("Microphone unavailable")
            _capsuleState.value = CapsuleState.Idle
        }
    }

    private fun stopAndTranscribe() {
        _capsuleState.value = CapsuleState.Transcribing
        transcribeJob = serviceScope.launch {
            val audio = try {
                recorder.stopAndCollect()
            } catch (t: Throwable) {
                Log.e(TAG, "stopAndCollect failed", t)
                _capsuleState.value = CapsuleState.Idle
                return@launch
            }
            Log.i(TAG, "captured ${audio.size} samples (${audio.size / 16000f}s)")
            if (audio.isEmpty()) {
                Log.w(TAG, "audio buffer empty — nothing to transcribe")
                _capsuleState.value = CapsuleState.Idle
                return@launch
            }
            try {
                val engine = ensureEngine()
                val settings = settingsFlow.value
                val started = System.currentTimeMillis()
                val rawText = engine.transcribe(
                    audio = audio,
                    language = settings.language.takeIf { it != Settings.LANGUAGE_AUTO },
                    translate = false,
                    threads = WhisperEngine.defaultThreadCount(),
                )
                val elapsed = System.currentTimeMillis() - started
                Log.i(TAG, "transcribed in ${elapsed}ms: \"${rawText.take(200)}\"")
                commit(rawText, settings)
            } catch (t: Throwable) {
                Log.e(TAG, "transcription failed", t)
                toast("Transcription failed")
            } finally {
                _capsuleState.value = CapsuleState.Idle
                transcribeJob = null
            }
        }
    }

    private suspend fun ensureEngine(): WhisperEngine {
        engine?.let { return it }
        val created = WhisperEngine.load(downloader.modelFile)
        engine = created
        return created
    }

    /**
     * Apply post-processing rules from [Settings] and commit the text into the
     * currently focused text field.
     */
    private fun commit(rawText: String, settings: Settings) {
        val processed = postProcess(rawText, settings)
        if (processed.isEmpty()) {
            Log.w(TAG, "commit skipped: processed text empty (raw was ${rawText.length} chars)")
            return
        }
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "commit skipped: currentInputConnection is null")
            return
        }
        val ok = ic.commitText(processed, 1)
        Log.i(TAG, "commitText(\"$processed\") -> $ok")
    }

    private fun postProcess(s: String, settings: Settings): String {
        var out = s.trim()
        if (out.isEmpty()) return out
        if (settings.trimTrailingPunctuation) {
            out = out.trimEnd('.', ',', '!', '?', ';', ':')
        }
        if (settings.lowercaseOutput) {
            out = out.lowercase()
        }
        if (out.isNotEmpty() && needsLeadingSpace()) {
            out = " $out"
        }
        return out
    }

    private fun needsLeadingSpace(): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(1, 0) ?: return false
        if (before.isEmpty()) return false
        val ch = before[0]
        // Insert a separator if we're directly after an alphanumeric or
        // closing punctuation character.
        return ch.isLetterOrDigit() || ch in ".,!?;:)]}\""
    }

    private fun openOnboarding() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "could not open onboarding", t)
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "SussurroIme"
    }
}
