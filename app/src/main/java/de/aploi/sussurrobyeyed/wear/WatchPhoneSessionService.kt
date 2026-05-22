package de.aploi.sussurrobyeyed.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import de.aploi.sussurrobyeyed.R
import de.aploi.sussurrobyeyed.audio.AudioRecorder
import de.aploi.sussurrobyeyed.audio.SilenceTrimmer
import de.aploi.sussurrobyeyed.data.Settings
import de.aploi.sussurrobyeyed.data.SettingsStore
import de.aploi.sussurrobyeyed.inject.TextInjector
import de.aploi.sussurrobyeyed.model.ModelDownloader
import de.aploi.sussurrobyeyed.shared.SessionState
import de.aploi.sussurrobyeyed.shared.WearableProtocol
import de.aploi.sussurrobyeyed.ui.MainActivity
import de.aploi.sussurrobyeyed.whisper.WhisperEngine
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Phone-side foreground service that owns the watch dictation session.
 *
 * Lifecycle:
 *  1. Watch sends [WearableProtocol.Paths.SESSION_START] -> we promote to
 *     foreground (FGS_TYPE_MICROPHONE not needed; the *watch* is the mic) and
 *     pre-warm Whisper.
 *  2. Watch opens [WearableProtocol.Paths.AUDIO_CHANNEL] -> we read the
 *     channel's input stream, decoding 16-bit LE PCM into a float buffer.
 *  3. Watch sends [WearableProtocol.Paths.SESSION_STOP] -> we wait for the
 *     channel to drain (EOF), trim leading/trailing silence, run Whisper,
 *     and route the transcript through [TextInjector] (IME if Sussurro is
 *     selected; AccessibilityService otherwise).
 *  4. We notify the watch with a `SessionState.Committed` ping plus the
 *     transcript itself, then drop back to Idle and stop the service.
 *
 * The whole pipeline is gated on a session [Mutex] so concurrent watch
 * sessions can't tangle the engine state.
 */
class WatchPhoneSessionService : LifecycleService() {

    private val sessionMutex = Mutex()
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var settingsStore: SettingsStore
    private lateinit var downloader: ModelDownloader
    private lateinit var transport: PhoneWearableTransport
    private lateinit var settingsFlow: StateFlow<Settings>

    @Volatile private var engine: WhisperEngine? = null
    private val engineMutex = Mutex()
    private var prewarmJob: Job? = null

    /** Buffered float samples received from the active channel, if any. */
    private val capture = MutableStateFlow<MutableList<Float>?>(null)
    private var captureJob: Job? = null
    private var currentChannel: ChannelClient.Channel? = null
    private var watchNodeId: String? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)

        settingsStore = SettingsStore(applicationContext)
        downloader = ModelDownloader(applicationContext)
        transport = PhoneWearableTransport(applicationContext)
        settingsFlow = settingsStore.settings.stateIn(
            scope = lifecycleScope,
            started = SharingStarted.Eagerly,
            initialValue = Settings(),
        )

        // The phone foreground service doesn't itself capture audio (the
        // watch does), so we don't need FGS_TYPE_MICROPHONE — declaring it
        // would force users to grant the phone microphone permission again
        // which is misleading. Use the regular dataSync FGS type instead.
        startForeground(NOTIFICATION_ID, buildNotification(SessionState.Idle, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val nodeId = intent?.getStringExtra(EXTRA_NODE_ID)
        if (nodeId != null) watchNodeId = nodeId

        when (intent?.action) {
            ACTION_SESSION_START -> handleStart(nodeId)
            ACTION_AUDIO_CHANNEL -> {
                val channel = intent.getParcelableExtraCompat(EXTRA_CHANNEL, ChannelClient.Channel::class.java)
                if (channel != null) handleChannelOpened(channel)
                else Log.w(TAG, "ACTION_AUDIO_CHANNEL with null channel")
            }
            ACTION_SESSION_STOP -> handleStop(nodeId)
            ACTION_SESSION_CANCEL -> handleCancel(nodeId)
            ACTION_USER_STOP -> handleUserStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(nodeId: String?) {
        ioScope.launch {
            updateNotification(SessionState.Recording, null)
            nodeId?.let { transport.sendState(it, SessionState.Recording) }
            // Pre-warm the engine in parallel so the encoder is hot when the
            // user taps stop.
            prewarmEngine()
        }
    }

    private fun handleChannelOpened(channel: ChannelClient.Channel) {
        Log.i(TAG, "starting capture from ${channel.nodeId}")
        // Detach any prior capture before binding a new one — the user may
        // have rapid-fire restarted from the watch.
        captureJob?.cancel()
        currentChannel?.let { runCatching { Wearable.getChannelClient(applicationContext).close(it) } }
        currentChannel = channel
        capture.value = ArrayList(WearableProtocol.AUDIO_SAMPLE_RATE * 8)

        captureJob = ioScope.launch {
            val client = Wearable.getChannelClient(applicationContext)
            val stream: InputStream = try {
                Tasks.await(client.getInputStream(channel))
            } catch (t: Throwable) {
                Log.e(TAG, "getInputStream failed", t)
                watchNodeId?.let { transport.sendState(it, SessionState.Error, "channel error") }
                return@launch
            }

            try {
                val buf = ByteArray(4096)
                val byteBuffer = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                while (isActive) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    val samples = capture.value ?: break
                    val shortCount = n / WearableProtocol.AUDIO_BYTES_PER_SAMPLE
                    byteBuffer.position(0)
                    for (i in 0 until shortCount) {
                        val s = byteBuffer.short.toInt()
                        samples.add(s / 32768f)
                    }
                    // Reset the buffer view so the next iteration writes from 0.
                    byteBuffer.clear()
                }
                Log.i(TAG, "capture stream EOF, ${capture.value?.size ?: 0} samples")
            } catch (t: Throwable) {
                Log.e(TAG, "capture loop crashed", t)
            } finally {
                runCatching { stream.close() }
            }
        }
    }

    private fun handleStop(nodeId: String?) {
        ioScope.launch {
            sessionMutex.withLock {
                Log.i(TAG, "session stop: waiting for capture drain")
                updateNotification(SessionState.Transcribing, null)
                nodeId?.let { transport.sendState(it, SessionState.Transcribing) }

                // Wait for the capture job to finish reading the channel.
                captureJob?.join()
                captureJob = null
                currentChannel = null

                val samplesList = capture.value ?: emptyList()
                val samples = FloatArray(samplesList.size).also { arr ->
                    for (i in arr.indices) arr[i] = samplesList[i]
                }
                capture.value = null

                if (samples.isEmpty()) {
                    Log.w(TAG, "no audio captured; aborting")
                    nodeId?.let { transport.sendState(it, SessionState.Error, "no audio") }
                    updateNotification(SessionState.Idle, null)
                    stopSelf()
                    return@withLock
                }

                runTranscription(samples, nodeId)
            }
        }
    }

    private suspend fun runTranscription(samples: FloatArray, nodeId: String?) {
        val trim = SilenceTrimmer.trim(samples, AudioRecorder.SAMPLE_RATE)
        val minAudioSamples = AudioRecorder.SAMPLE_RATE / 5 // 200 ms
        val toFeed = if (trim.trimmed.size >= minAudioSamples) trim.trimmed else samples

        val text = try {
            val engine = ensureEngine()
            val settings = settingsFlow.first()
            val started = System.currentTimeMillis()
            val raw = engine.transcribe(
                audio = toFeed,
                language = settings.language.takeIf { it != Settings.LANGUAGE_AUTO },
                translate = false,
                threads = WhisperEngine.defaultThreadCount(),
            )
            Log.i(TAG, "watch transcribe ok in ${System.currentTimeMillis() - started}ms (${raw.length} chars)")
            raw
        } catch (t: Throwable) {
            Log.e(TAG, "watch transcription failed", t)
            nodeId?.let { transport.sendState(it, SessionState.Error, "whisper failed") }
            updateNotification(SessionState.Idle, null)
            stopSelf()
            return
        }

        if (text.isBlank()) {
            Log.w(TAG, "transcription empty")
            nodeId?.let { transport.sendState(it, SessionState.Error, "no speech") }
            updateNotification(SessionState.Idle, null)
            stopSelf()
            return
        }

        val injection = TextInjector.inject(applicationContext, text)
        if (injection.ok) {
            // Surface the committed text to the settings screen so the
            // user can confirm what their watch dictation became. Reset
            // happens implicitly: the value lives in a process singleton
            // that dies with the app process.
            LastWatchTranscript.set(text)
            nodeId?.let {
                transport.sendTranscript(it, text)
                transport.sendState(it, SessionState.Committed)
            }
            updateNotification(SessionState.Committed, "via ${injection.reason}")
        } else {
            nodeId?.let { transport.sendState(it, SessionState.Error, injection.reason) }
            updateNotification(SessionState.Error, injection.reason)
        }
        stopSelf()
    }

    private fun handleCancel(nodeId: String?) {
        ioScope.launch {
            captureJob?.cancel()
            captureJob = null
            currentChannel = null
            capture.value = null
            nodeId?.let { transport.sendState(it, SessionState.Idle) }
            updateNotification(SessionState.Idle, null)
            stopSelf()
        }
    }

    /**
     * The user tapped "Stop" on the phone notification. We need to do
     * everything [handleCancel] does AND ask the watch to release its
     * mic — the watch's recorder is still running because nothing on
     * its end has been told to bail out.
     */
    private fun handleUserStop() {
        ioScope.launch {
            // Tell the watch first; the longer the BT round-trip, the
            // longer the user hears nothing. Best-effort: even if the
            // message fails, the local teardown still runs.
            watchNodeId?.let {
                runCatching { transport.sendCancel(it) }
            }
            captureJob?.cancel()
            captureJob = null
            currentChannel = null
            capture.value = null
            watchNodeId?.let { transport.sendState(it, SessionState.Idle) }
            updateNotification(SessionState.Idle, null)
            stopSelf()
        }
    }

    private fun prewarmEngine() {
        if (engine != null) return
        if (prewarmJob?.isActive == true) return
        if (!downloader.isInstalled()) {
            Log.w(TAG, "model not installed; cannot pre-warm")
            return
        }
        prewarmJob = ioScope.launch {
            try { ensureEngine() } catch (t: Throwable) { Log.w(TAG, "pre-warm failed", t) }
        }
    }

    private suspend fun ensureEngine(): WhisperEngine {
        engine?.let { return it }
        return engineMutex.withLock {
            engine?.let { return@withLock it }
            // Note: `WhisperEngine.load` takes only the model file today.
            // A separate native-dispatch refactor may extend this signature
            // to pass `applicationInfo.nativeLibraryDir` for dlopening
            // CPU-variant backends; when that lands, update this call site
            // and the IME's mirror.
            val created = withContext(Dispatchers.IO) {
                WhisperEngine.load(downloader.modelFile)
            }
            engine = created
            created
        }
    }

    override fun onDestroy() {
        captureJob?.cancel()
        currentChannel?.let { runCatching { Wearable.getChannelClient(applicationContext).close(it) } }
        engine?.let { e ->
            try { runBlocking { e.release() } } catch (t: Throwable) { Log.w(TAG, "engine release failed", t) }
        }
        engine = null
        ioScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun updateNotification(state: SessionState, reason: String?) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state, reason))
    }

    private fun buildNotification(state: SessionState, reason: String?): Notification {
        val title = when (state) {
            SessionState.Recording -> getString(R.string.watch_session_recording)
            SessionState.Transcribing -> getString(R.string.watch_session_transcribing)
            SessionState.Committed -> getString(R.string.watch_session_committed)
            SessionState.Error -> getString(R.string.watch_session_error)
            else -> getString(R.string.watch_session_idle)
        }
        val text = reason ?: getString(R.string.watch_session_subtext)

        // Content tap → back into Sussurro. We intentionally don't try to
        // jump back to whatever the user came from: Android already keeps
        // the previous app on the back stack, and a notification that
        // navigates "somewhere unexpected" reads worse than one that goes
        // home base.
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Stop action: drives ACTION_USER_STOP which also pings the watch
        // so its recorder releases the mic. Only attached while there's
        // an active session — once we're Committed/Error/Idle the action
        // wouldn't have anything to stop.
        val active = state == SessionState.Recording || state == SessionState.Transcribing
        val stopPi = if (active) {
            val stopIntent = Intent(this, WatchPhoneSessionService::class.java).apply {
                action = ACTION_USER_STOP
            }
            PendingIntent.getService(
                this, REQUEST_STOP, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(active)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (stopPi != null) {
                    addAction(
                        android.R.drawable.ic_media_pause,
                        getString(R.string.watch_session_stop_action),
                        stopPi,
                    )
                }
            }
            .build()
    }

    companion object {
        const val ACTION_SESSION_START = "de.aploi.sussurrobyeyed.action.WATCH_START"
        const val ACTION_AUDIO_CHANNEL = "de.aploi.sussurrobyeyed.action.WATCH_AUDIO"
        const val ACTION_SESSION_STOP = "de.aploi.sussurrobyeyed.action.WATCH_STOP"
        const val ACTION_SESSION_CANCEL = "de.aploi.sussurrobyeyed.action.WATCH_CANCEL"

        /**
         * User tapped "Stop" on the phone-side dictation notification.
         * Drives the local teardown plus a PHONE_CANCEL ping to the watch
         * so the recorder there releases the mic too.
         */
        const val ACTION_USER_STOP = "de.aploi.sussurrobyeyed.action.WATCH_USER_STOP"

        const val EXTRA_NODE_ID = "node_id"
        const val EXTRA_CHANNEL = "channel"

        private const val TAG = "WatchPhoneSession"
        private const val CHANNEL_ID = "sussurro_watch_session"
        private const val NOTIFICATION_ID = 2001
        // Distinct PendingIntent request code so the notification's Stop
        // action doesn't share a slot with the content intent.
        private const val REQUEST_STOP = 1

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            context.getString(R.string.watch_session_channel),
                            NotificationManager.IMPORTANCE_LOW,
                        ),
                    )
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> Intent.getParcelableExtraCompat(name: String, clazz: Class<T>): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name) as? T
    }
}
