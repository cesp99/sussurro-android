package de.aploi.sussurrobyeyed.wear.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import de.aploi.sussurrobyeyed.shared.SessionState
import de.aploi.sussurrobyeyed.wear.MainActivity
import de.aploi.sussurrobyeyed.wear.R
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the recording session.
 *
 * Wear OS will gleefully kill background apps the moment the user lowers
 * their wrist, so the recorder must run inside a started, foreground
 * service tagged as `microphone`. We re-use [WatchSessionController] for
 * the actual state, this class is only the lifecycle adapter.
 */
class WatchSessionService : LifecycleService() {

    /**
     * Partial wake lock held only while the session is actively recording.
     *
     * Wear OS aggressively suspends the CPU once the screen turns off
     * (e.g. wrist down), which can stall the AudioRecord read loop and
     * starve the BT-channel writer — observable as choppy audio and a
     * dropout right after the user lowers their wrist mid-dictation.
     * A PARTIAL_WAKE_LOCK keeps the CPU running just enough to keep the
     * mic pump alive; we release it the moment we leave Recording.
     */
    private var recordingWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)

        // Promote to foreground immediately so we can claim the mic.
        startForeground(NOTIFICATION_ID, buildNotification(SessionState.Recording),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        // Track session state and update the OngoingActivity / shut down
        // the service when we drop back to Idle. StateFlow already
        // de-duplicates emissions via Operator Fusion, so collect directly.
        lifecycleScope.launch {
            WatchSessionController.state.collect { state ->
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(state))

                // Pair the wake lock to the Recording state. Transcribing
                // happens on the phone, so we can let the CPU sleep again
                // as soon as the recorder stops streaming. Acquire/release
                // is idempotent via the WakeLock's referenceCounted=false
                // default in [acquireRecordingWakeLock].
                if (state == SessionState.Recording) {
                    acquireRecordingWakeLock()
                } else {
                    releaseRecordingWakeLock()
                }

                if (state == SessionState.Idle) {
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        // Belt-and-braces: if the system tears us down while a session was
        // still flagged Recording (unusual but possible on memory pressure)
        // we'd leak the wake lock otherwise.
        releaseRecordingWakeLock()
        super.onDestroy()
    }

    private fun acquireRecordingWakeLock() {
        if (recordingWakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            // We manually pair acquire/release to the Recording state; the
            // ref-counted variant would let stray collects accumulate
            // ghost acquisitions.
            setReferenceCounted(false)
        }
        try {
            wl.acquire(MAX_DURATION_MS)
            recordingWakeLock = wl
        } catch (t: Throwable) {
            Log.w(TAG, "wake lock acquire failed", t)
        }
    }

    private fun releaseRecordingWakeLock() {
        recordingWakeLock?.let { wl ->
            if (wl.isHeld) {
                runCatching { wl.release() }
            }
        }
        recordingWakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> WatchSessionController.startInBackground(applicationContext)
            ACTION_STOP -> WatchSessionController.stopInBackground(applicationContext)
            ACTION_CANCEL -> WatchSessionController.cancelInBackground(applicationContext)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun buildNotification(state: SessionState): Notification {
        val title = when (state) {
            SessionState.Recording -> getString(R.string.notification_session_listening)
            SessionState.Transcribing -> getString(R.string.notification_session_transcribing)
            else -> getString(R.string.app_name)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Wear OS' OngoingActivity makes the running session appear in the
        // recents row & on the watch face's status chip — required so the
        // user can swipe back into Sussurro from anywhere on the watch.
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(android.R.drawable.ic_btn_speak_now)
            .setTouchIntent(contentPi)
            .setStatus(Status.Builder().addTemplate(title).build())
            .build()
            .apply(applicationContext)

        return builder.build()
    }

    companion object {
        const val ACTION_START = "de.aploi.sussurrobyeyed.wear.action.START"
        const val ACTION_STOP = "de.aploi.sussurrobyeyed.wear.action.STOP"
        const val ACTION_CANCEL = "de.aploi.sussurrobyeyed.wear.action.CANCEL"

        private const val TAG = "WatchSessionService"
        private const val CHANNEL_ID = "sussurro_session"
        private const val NOTIFICATION_ID = 1001

        // Wake lock tag pattern recommended by AOSP: "<package>:<purpose>".
        // Surfaces clearly in `adb shell dumpsys power` if it ever leaks.
        private const val WAKE_LOCK_TAG = "Sussurro:WatchRecording"

        // Safety ceiling for the wake lock acquire timeout. Matches the
        // recorder's MAX_DURATION_SECONDS plus a generous margin so the
        // OS auto-releases the lock if our normal Recording → !Recording
        // transition gets stuck somewhere we didn't anticipate.
        private const val MAX_DURATION_MS: Long = 90_000L

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            context.getString(R.string.notification_channel_session),
                            NotificationManager.IMPORTANCE_LOW,
                        ),
                    )
                }
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, WatchSessionService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WatchSessionService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, WatchSessionService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }
}
