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

                if (state == SessionState.Idle) {
                    stopSelf()
                }
            }
        }
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

        private const val CHANNEL_ID = "sussurro_session"
        private const val NOTIFICATION_ID = 1001

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
