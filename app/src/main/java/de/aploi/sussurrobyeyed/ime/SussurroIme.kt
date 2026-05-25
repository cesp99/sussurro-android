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
import de.aploi.sussurrobyeyed.audio.SilenceTrimmer
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 *
 * In addition to its own UI, the IME also exposes [tryCommitFromExternal] for
 * the watch session pipeline: when the user dictates from their watch and
 * Sussurro IME happens to be the active keyboard, the WatchSessionService
 * routes the transcript through the IME's existing input connection rather
 * than going around it via accessibility, so undo and autocorrect work the
 * way the user expects.
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

    @Volatile private var engine: WhisperEngine? = null
    private val engineMutex = Mutex()
    private var transcribeJob: Job? = null
    private var prewarmJob: Job? = null

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

        // Register ourselves as the live IME instance so the watch pipeline
        // can route transcripts through the host text field's input
        // connection when the user has Sussurro selected.
        liveImeRef = this
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

        // Pre-warm the whisper.cpp context so the *first* tap doesn't pay the
        // model-load cost on the foreground thread of the host app. Cheap to
        // call repeatedly — ensureEngine() is idempotent and the mutex inside
        // it serialises concurrent attempts.
        prewarmEngineIfPossible()
    }

    private fun prewarmEngineIfPossible() {
        if (engine != null) return
        if (prewarmJob?.isActive == true) return
        if (!downloader.isInstalled()) return
        prewarmJob = serviceScope.launch {
            try {
                ensureEngine()
            } catch (t: Throwable) {
                Log.w(TAG, "engine pre-warm failed", t)
            }
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
        if (liveImeRef === this) liveImeRef = null
        // Let the framework finish its teardown (which calls onFinishInputView)
        // before we mark the lifecycle DESTROYED; otherwise the back-transition
        // crashes.
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    /**
     * Commit [text] into the currently bound input field via the IME's
     * standard `InputConnection`. Public hook used by the watch session
     * pipeline; mirrors [commit] but takes a settings-applied string
     * directly instead of running it through Whisper post-processing
     * twice.
     *
     * @return true if the host accepted the commit. False when there's no
     *   input connection (e.g. the keyboard isn't visible / nothing
     *   focused).
     */
    fun commitFromExternal(text: String): Boolean {
        if (text.isEmpty()) return false
        val processed = postProcess(text, settingsFlow.value)
        if (processed.isEmpty()) return false
        val ic = currentInputConnection ?: run {
            Log.w(TAG, "commitFromExternal: no input connection")
            return false
        }
        val ok = ic.commitText(processed, 1)
        Log.i(TAG, "commitFromExternal len=${processed.length} ok=$ok")
        return ok
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

            // Strip leading/trailing silence so Whisper's encoder doesn't waste
            // a 30 s padded frame on dead air. Fall back to the raw buffer if
            // the trimmer left us with too little signal (e.g. trimmer flagged
            // a soft-spoken capture as silence) — better to ask Whisper to
            // decode quiet audio than to drop it on the floor.
            val trim = SilenceTrimmer.trim(audio, AudioRecorder.SAMPLE_RATE)
            val minAudioSamples = AudioRecorder.SAMPLE_RATE / 5 // 200 ms
            val audioForWhisper = if (trim.trimmed.size >= minAudioSamples) {
                Log.i(
                    TAG,
                    "silence trim: kept ${trim.trimmed.size}/${audio.size} samples " +
                        "(lead=${trim.leadingSamplesCut}, trail=${trim.trailingSamplesCut}, " +
                        "threshold=%.4f)".format(trim.rmsThreshold),
                )
                trim.trimmed
            } else {
                Log.w(
                    TAG,
                    "silence trim too aggressive (kept ${trim.trimmed.size} samples), " +
                        "falling back to raw buffer",
                )
                audio
            }

            try {
                val engine = ensureEngine()
                val settings = settingsFlow.value
                val started = System.currentTimeMillis()
                val rawText = engine.transcribe(
                    audio = audioForWhisper,
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
        // Fast path: already loaded.
        engine?.let { return it }
        // Slow path: serialise concurrent loads (prewarm + first tap can race).
        return engineMutex.withLock {
            engine?.let { return@withLock it }
            val libDir = applicationContext.applicationInfo.nativeLibraryDir
            val created = withContext(Dispatchers.IO) {
                WhisperEngine.load(downloader.modelFile, libDir)
            }
            engine = created
            created
        }
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

        /**
         * Volatile pointer to the live IME instance, populated in [onCreate]
         * and cleared in [onDestroy]. The framework only ever runs at most
         * one [SussurroIme] at a time so a singleton ref is safe.
         *
         * Public accessor goes through [tryCommitFromExternal] so external
         * callers don't depend on the lifecycle directly.
         */
        @Volatile
        private var liveImeRef: SussurroIme? = null

        /**
         * Attempt to commit [text] through the currently-active IME instance.
         * Returns true only when SussurroIme is bound to a host text field
         * AND the host accepts the commit; in every other case (no live
         * IME, no input connection, host refuses) the caller should fall
         * back to the accessibility path.
         */
        fun tryCommitFromExternal(text: String): Boolean {
            val ime = liveImeRef ?: return false
            return runCatching { ime.commitFromExternal(text) }.getOrDefault(false)
        }
    }
}
