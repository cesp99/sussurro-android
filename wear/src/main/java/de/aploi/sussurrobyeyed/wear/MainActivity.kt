package de.aploi.sussurrobyeyed.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.aploi.sussurrobyeyed.wear.session.WatchSessionService
import de.aploi.sussurrobyeyed.wear.ui.WatchScreen

/**
 * The watch's only Activity.
 *
 * Hosts [WatchScreen] in a Compose tree. The activity is mostly a thin
 * adapter: tap-to-toggle and microphone permission ferrying. The actual
 * recording / wearable I/O lives in
 * [de.aploi.sussurrobyeyed.wear.session.WatchSessionService] and
 * [de.aploi.sussurrobyeyed.wear.session.WatchSessionController].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Make sure the notification channel exists before the service kicks
        // it off so promoting to foreground doesn't race with channel
        // creation on Android 13+.
        WatchSessionService.ensureChannel(this)

        setContent {
            SussurroWatchApp()
        }
    }
}

@Composable
private fun SussurroWatchApp() {
    val context = LocalContext.current

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        if (granted) {
            // The user just enabled the mic; start the session right away
            // so they don't have to tap a second time.
            WatchSessionService.start(context)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        micGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        // Ask for POST_NOTIFICATIONS implicitly via the same launcher when
        // we ever need it — for now the mic is the only blocker.
    }

    WatchScreen(
        micGranted = micGranted,
        onRequestMic = {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onStartSession = { WatchSessionService.start(context) },
        onStopSession = { WatchSessionService.stop(context) },
        onCancelSession = { WatchSessionService.cancel(context) },
    )
}
