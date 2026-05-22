package de.aploi.sussurrobyeyed.wear.session

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import de.aploi.sussurrobyeyed.shared.SessionState
import de.aploi.sussurrobyeyed.wear.audio.WatchAudioRecorder
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for the watch's dictation session.
 *
 * Process-singleton so the [com.google.android.gms.wearable.WearableListenerService]
 * (which lives outside the activity) and the UI layer agree on the same
 * [state] and [rms] flows. The [androidx.lifecycle.LifecycleService] in
 * [WatchSessionService] holds the foreground notification while a session
 * is actually running.
 *
 * Lifecycle:
 *  - [start] opens an audio channel to the phone, kicks off the recorder,
 *    and sends a SESSION_START ping. Idempotent.
 *  - [stop] flips state to [SessionState.Transcribing], stops the recorder,
 *    closes the channel and sends SESSION_STOP. The phone replies with
 *    PHONE_STATE updates (committed/error) which [updatePhoneState] applies.
 *  - [cancel] tears the session down without asking the phone to transcribe.
 */
internal object WatchSessionController {

    private const val TAG = "WatchSessionController"

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val transitionMutex = Mutex()

    val recorder = WatchAudioRecorder()

    private val _state = MutableStateFlow(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * Optional textual reason associated with the current state — populated
     * for [SessionState.Error] (e.g. "Phone not paired") and cleared on the
     * next non-error transition.
     */
    private val _reason = MutableStateFlow<String?>(null)
    val reason: StateFlow<String?> = _reason.asStateFlow()

    /** The most recent transcript echoed back from the phone, if any. */
    private val _lastTranscript = MutableStateFlow<String?>(null)
    val lastTranscript: StateFlow<String?> = _lastTranscript.asStateFlow()

    private var transport: WearableTransport? = null
    private var phoneNodeId: String? = null
    private var channel: ChannelClient.Channel? = null
    private var sink: OutputStream? = null
    private var streamJob: Job? = null
    private var maxDurationWatchdog: Job? = null

    /** Convenience wrapper that lazy-initialises the transport for [appContext]. */
    private fun transport(appContext: Context): WearableTransport =
        transport ?: WearableTransport(appContext).also { transport = it }

    fun startInBackground(appContext: Context) {
        scope.launch { start(appContext) }
    }

    fun stopInBackground(appContext: Context) {
        scope.launch { stop(appContext) }
    }

    fun cancelInBackground(appContext: Context) {
        scope.launch { cancel(appContext) }
    }

    suspend fun start(appContext: Context) {
        transitionMutex.withLock {
            if (_state.value == SessionState.Recording || _state.value == SessionState.Transcribing) return
            _reason.value = null
            _lastTranscript.value = null

            val tx = transport(appContext)
            val node = tx.findPhoneNodeId()
            if (node == null) {
                Log.w(TAG, "no paired phone node; aborting session start")
                _state.value = SessionState.Error
                _reason.value = "phone not reachable"
                return
            }
            phoneNodeId = node

            // Tell the phone the watch is about to stream audio so it can
            // light up its foreground service / engine warm-up before any
            // bytes hit the channel.
            tx.sendStart(node)

            val ch = tx.openAudioChannel(node) ?: run {
                _state.value = SessionState.Error
                _reason.value = "channel failed"
                return
            }
            channel = ch
            val out = tx.outputStream(ch) ?: run {
                tx.closeChannel(ch)
                channel = null
                _state.value = SessionState.Error
                _reason.value = "channel failed"
                return
            }
            sink = out

            recorder.start(scope, out)
            _state.value = SessionState.Recording

            // The recorder caps itself at MAX_DURATION_SECONDS. When that
            // fires we want to act like the user tapped stop, including
            // closing the channel and asking the phone to transcribe —
            // but with a "max length reached" reason so the watch UI can
            // tell the user *why* it stopped on its own.
            maxDurationWatchdog?.cancel()
            maxDurationWatchdog = scope.launch {
                recorder.hitMaxDuration.first { it }
                if (_state.value == SessionState.Recording) {
                    _reason.value = "max length reached"
                    stop(appContext)
                }
            }
        }
    }

    suspend fun stop(appContext: Context) {
        transitionMutex.withLock {
            if (_state.value != SessionState.Recording) return
            _state.value = SessionState.Transcribing

            // The watchdog has either already fired (self-cap path) or is
            // no longer useful (user-initiated stop). Either way, drop it.
            maxDurationWatchdog?.cancel()
            maxDurationWatchdog = null

            recorder.stop()
            // Close the channel after the recorder fully drains; this
            // signals EOF to the phone-side reader.
            sink?.let { runCatching { it.flush() } }
            sink?.let { runCatching { it.close() } }
            sink = null
            channel?.let { transport(appContext).closeChannel(it) }
            channel = null

            phoneNodeId?.let { transport(appContext).sendStop(it) }
        }
    }

    suspend fun cancel(appContext: Context) {
        transitionMutex.withLock {
            maxDurationWatchdog?.cancel()
            maxDurationWatchdog = null

            recorder.cancel()
            sink?.let { runCatching { it.close() } }
            sink = null
            channel?.let { transport(appContext).closeChannel(it) }
            channel = null
            phoneNodeId?.let { transport(appContext).sendCancel(it) }
            phoneNodeId = null
            _state.value = SessionState.Idle
            _reason.value = null
        }
    }

    /**
     * Apply a state update received from the phone over MessageClient.
     * Called by [WatchMessageListener] on the wearable threadpool.
     */
    fun updatePhoneState(state: SessionState, reason: String?) {
        // Guard against the phone trying to roll us back to Recording while
        // the watch's recorder isn't active — that would split-brain the UI.
        when (state) {
            SessionState.Recording -> {
                // Only respect this if the watch agrees the recorder is up.
                if (recorder.state.value == WatchAudioRecorder.State.Recording) {
                    _state.value = SessionState.Recording
                }
            }
            else -> {
                _state.value = state
                _reason.value = reason
            }
        }

        if (state == SessionState.Committed || state == SessionState.Error) {
            // Auto-fade back to Idle so the user can start another session
            // without manually clearing the message. Keep the "committed"
            // banner up for a moment first, handled in the UI.
            scope.launch {
                kotlinx.coroutines.delay(2_500)
                if (_state.value == SessionState.Committed || _state.value == SessionState.Error) {
                    _state.value = SessionState.Idle
                    _reason.value = null
                }
            }
        }
    }

    fun updateLastTranscript(text: String) {
        _lastTranscript.value = text
    }
}
