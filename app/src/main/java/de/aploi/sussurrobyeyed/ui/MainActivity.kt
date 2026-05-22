package de.aploi.sussurrobyeyed.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.aploi.sussurrobyeyed.data.Settings
import de.aploi.sussurrobyeyed.data.SettingsStore
import de.aploi.sussurrobyeyed.data.ThemeMode
import de.aploi.sussurrobyeyed.inject.TextInjector
import de.aploi.sussurrobyeyed.model.ModelDownloader
import de.aploi.sussurrobyeyed.ui.screens.OnboardingScreen
import de.aploi.sussurrobyeyed.ui.screens.SettingsScreen
import de.aploi.sussurrobyeyed.ui.theme.SussurroTheme

/**
 * Sussurro's only Activity.
 *
 * Hosts two destinations: [OnboardingScreen] (when one or more prerequisites
 * are missing) and [SettingsScreen] (the steady-state UI). The keyboard itself
 * lives in [de.aploi.sussurrobyeyed.ime.SussurroIme] — this Activity exists to
 * walk the user through enabling it and to expose settings.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val store = SettingsStore(applicationContext)
        val downloader = ModelDownloader(applicationContext)

        setContent {
            val settings by store.settings.collectAsState(initial = Settings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            SussurroTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier,
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SussurroApp(
                        settings = settings,
                        store = store,
                        downloader = downloader,
                    )
                }
            }
        }
    }
}

@Composable
private fun SussurroApp(
    settings: Settings,
    store: SettingsStore,
    downloader: ModelDownloader,
) {
    val context = LocalContext.current

    // Re-check every prerequisite whenever we resume so onboarding stops as
    // soon as the last gate flips.
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var modelInstalled by remember { mutableStateOf(downloader.isInstalled()) }
    var imeEnabled by remember { mutableStateOf(isImeEnabled(context)) }
    var imeSelected by remember { mutableStateOf(isImeSelected(context)) }
    // Accessibility is optional — it powers the "inject into any focused
    // field" path used by the watch companion when Sussurro isn't the
    // active keyboard. Tracked here so the onboarding card can refresh
    // when the user comes back from system settings.
    var accessibilityEnabled by remember { mutableStateOf(TextInjector.isAccessibilityEnabled(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        modelInstalled = downloader.isInstalled()
        imeEnabled = isImeEnabled(context)
        imeSelected = isImeSelected(context)
        accessibilityEnabled = TextInjector.isAccessibilityEnabled(context)
    }

    // Optional steps (accessibility, watch companion) deliberately stay
    // out of `allReady` — the user can ship without them and we don't
    // want to keep them stuck in onboarding for hardware they don't own.
    val allReady = permissionGranted && modelInstalled && imeEnabled && imeSelected
    // `onboardingComplete` is sticky — once the user has finished onboarding
    // we don't yank them back if e.g. they later revoke the IME selection.
    var onboardingComplete by remember { mutableStateOf(allReady) }
    LaunchedEffect(allReady) {
        if (allReady) onboardingComplete = true
    }

    val refreshModelInstalled: () -> Unit = { modelInstalled = downloader.isInstalled() }

    if (!onboardingComplete) {
        OnboardingScreen(
            permissionGranted = permissionGranted,
            modelInstalled = modelInstalled,
            imeEnabled = imeEnabled,
            imeSelected = imeSelected,
            accessibilityEnabled = accessibilityEnabled,
            downloader = downloader,
            settings = settings,
            store = store,
            onPermissionResult = { granted -> permissionGranted = granted },
            onOpenSettings = { onboardingComplete = true },
            onModelStateChanged = refreshModelInstalled,
        )
    } else {
        SettingsScreen(
            settings = settings,
            store = store,
            modelInstalled = modelInstalled,
            downloader = downloader,
            onMissingModel = {
                refreshModelInstalled()
                onboardingComplete = false
            },
        )
    }
}

private fun isImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
    val ours = "${context.packageName}/de.aploi.sussurrobyeyed.ime.SussurroIme"
    return imm.enabledInputMethodList.any { it.id == ours }
}

private fun isImeSelected(context: Context): Boolean {
    val ours = "${context.packageName}/de.aploi.sussurrobyeyed.ime.SussurroIme"
    val current = AndroidSettings.Secure.getString(
        context.contentResolver,
        AndroidSettings.Secure.DEFAULT_INPUT_METHOD,
    )
    return current == ours
}
