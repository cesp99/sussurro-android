package de.aploi.sussurrobyeyed.ime

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.aploi.sussurrobyeyed.BuildConfig
import de.aploi.sussurrobyeyed.R
import de.aploi.sussurrobyeyed.data.Settings
import de.aploi.sussurrobyeyed.data.ThemeMode
import de.aploi.sussurrobyeyed.model.ModelDownloader
import de.aploi.sussurrobyeyed.ui.components.CapsuleState
import de.aploi.sussurrobyeyed.ui.components.SussurroCapsule
import de.aploi.sussurrobyeyed.ui.theme.SussurroTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * The Compose root that the [SussurroIme] hosts inside its `onCreateInputView`.
 *
 * Visually almost empty: a single [SussurroCapsule] centred horizontally with
 * a small hint line below it whenever the keyboard cannot transcribe yet
 * (missing permission or model). Height stays close to ~200 dp; we don't want
 * to monopolise the screen.
 */
@Composable
internal fun KeyboardSurface(
    capsuleStateFlow: StateFlow<CapsuleState>,
    rmsFlow: StateFlow<Float>,
    settingsFlow: StateFlow<Settings>,
    onCapsuleTap: () -> Unit,
) {
    val context = LocalContext.current
    val settings by settingsFlow.collectAsState()
    val capsuleState by capsuleStateFlow.collectAsState()
    val rms by rmsFlow.collectAsState()

    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    // The IME has to ask Android for permission via the host activity, so the
    // best we can do here is detect it and show a hint that taps the user back
    // into the main app.
    val hasMicPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    val downloader = remember { ModelDownloader(context.applicationContext) }
    val hasModel = remember(downloader) { downloader.isInstalled() }

    val hint: String? = when {
        !hasMicPermission -> stringResource(R.string.capsule_state_no_permission)
        !hasModel -> stringResource(R.string.capsule_state_no_model)
        else -> null
    }

    SussurroTheme(darkTheme = darkTheme) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SussurroCapsule(
                    state = capsuleState,
                    rms = rms,
                    onClick = onCapsuleTap,
                )
                if (hint != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (BuildConfig.DEBUG) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "build ${BuildConfig.BUILD_STAMP}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}
