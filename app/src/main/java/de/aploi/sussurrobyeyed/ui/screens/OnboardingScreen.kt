package de.aploi.sussurrobyeyed.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.aploi.sussurrobyeyed.R
import de.aploi.sussurrobyeyed.data.Settings
import de.aploi.sussurrobyeyed.data.SettingsStore
import de.aploi.sussurrobyeyed.inject.TextInjector
import de.aploi.sussurrobyeyed.model.ModelDownloader
import de.aploi.sussurrobyeyed.ui.components.LanguagePicker
import de.aploi.sussurrobyeyed.ui.components.SectionCard
import de.aploi.sussurrobyeyed.wear.WatchPresence
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The onboarding flow.
 *
 * Three numbered cards stacked vertically. Each card collapses (greys + check)
 * once its prerequisite is satisfied. When all three are satisfied the parent
 * flips to [SettingsScreen]; the user can also tap "Skip to settings" once the
 * permission card is done — handled via [onOpenSettings].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnboardingScreen(
    permissionGranted: Boolean,
    modelInstalled: Boolean,
    imeEnabled: Boolean,
    imeSelected: Boolean,
    accessibilityEnabled: Boolean,
    downloader: ModelDownloader,
    settings: Settings,
    store: SettingsStore,
    onPermissionResult: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onModelStateChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onPermissionResult(granted)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.setup_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.setup_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.setup_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            // Step 1: Microphone permission
            OnboardingCard(
                number = 1,
                title = stringResource(R.string.step_permission_title),
                description = if (permissionGranted) {
                    stringResource(R.string.step_permission_granted)
                } else {
                    stringResource(R.string.step_permission_desc)
                },
                completed = permissionGranted,
            ) {
                if (!permissionGranted) {
                    Button(onClick = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) {
                        Text(stringResource(R.string.step_permission_button))
                    }
                }
            }

            // Step 2: Whisper model
            ModelStepCard(
                modelInstalled = modelInstalled,
                downloader = downloader,
                onModelStateChanged = onModelStateChanged,
            )

            // Step 3: Enable IME
            OnboardingCard(
                number = 3,
                title = stringResource(R.string.step_enable_title),
                description = when {
                    imeSelected -> stringResource(R.string.step_enable_selected)
                    imeEnabled -> stringResource(R.string.step_enable_enabled)
                    else -> stringResource(R.string.step_enable_desc)
                },
                completed = imeEnabled && imeSelected,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!imeEnabled) {
                        Button(onClick = { openIMESettings(context) }) {
                            Text(stringResource(R.string.step_enable_enable))
                        }
                    }
                    if (imeEnabled && !imeSelected) {
                        Button(onClick = { showInputMethodPicker(context) }) {
                            Text(stringResource(R.string.step_enable_choose))
                        }
                    }
                }
            }

            // Optional Step 4: Accessibility service. Unlocks the "inject
            // dictation anywhere" path for when Sussurro IS NOT the
            // selected keyboard (e.g. watch dictation into a webview that's
            // currently using Gboard). Optional — the rest of the app still
            // works without it.
            AccessibilityCard(
                completed = accessibilityEnabled,
                onEnableClicked = { openAccessibilitySettings(context) },
            )

            // Optional Step 5: Watch companion. Pure status indicator —
            // installation of the watch APK is sideloaded for now, so we
            // don't try to deep-link into Play Store. The card just
            // reassures the user when a paired watch is detected.
            WatchCompanionCard()

            // Language: optional, but offered up-front so the user doesn't have
            // to hunt for it in settings to dictate in their own language.
            SectionCard(
                title = stringResource(R.string.settings_section_language),
                icon = { Icon(Icons.Filled.Language, contentDescription = null) },
            ) {
                LanguagePicker(
                    currentCode = settings.language,
                    onSelect = { scope.launch { store.setLanguage(it) } },
                )
            }

            // How to use: a short paragraph so the empty space below the setup
            // steps actually tells the user what to do once everything is green.
            SectionCard(
                title = stringResource(R.string.setup_how_to_use_title),
                icon = { Icon(Icons.Outlined.Lightbulb, contentDescription = null) },
            ) {
                Text(
                    text = stringResource(R.string.setup_how_to_use_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (permissionGranted && modelInstalled && imeEnabled && imeSelected) {
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.settings_title))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OnboardingCard(
    number: Int,
    title: String,
    description: String,
    completed: Boolean,
    content: @Composable () -> Unit,
) {
    val containerColor =
        if (completed) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StepBadge(number = number, completed = completed)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun StepBadge(number: Int, completed: Boolean) {
    Surface(
        modifier = Modifier.size(28.dp),
        shape = CircleShape,
        color = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (completed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (completed) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ModelStepCard(
    modelInstalled: Boolean,
    downloader: ModelDownloader,
    onModelStateChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val progress = remember { MutableStateFlow<ModelDownloader.Progress>(ModelDownloader.Progress.Idle) }
    // Compose lint (`FlowOperatorInvokedInComposition`) flags calling a flow
    // operator inside a composable; collect the MutableStateFlow directly
    // since it already conforms to StateFlow.
    val state by progress.collectAsState()

    var downloadJob: Job? by remember { mutableStateOf(null) }

    // If the model gets installed/deleted externally, sync our local state.
    DisposableEffect(modelInstalled) {
        progress.value = if (modelInstalled) ModelDownloader.Progress.Done else ModelDownloader.Progress.Idle
        onDispose { }
    }

    // Treat the model as installed as soon as the download flow reports Done,
    // not just when the parent's `modelInstalled` prop catches up on resume.
    val effectivelyInstalled = modelInstalled || state == ModelDownloader.Progress.Done

    val description = when (val s = state) {
        is ModelDownloader.Progress.Downloading ->
            stringResource(R.string.step_model_desc_downloading) +
                " (${(s.fraction * 100).toInt()}%)"
        ModelDownloader.Progress.Verifying -> stringResource(R.string.step_model_desc_downloading)
        ModelDownloader.Progress.Done -> stringResource(R.string.step_model_desc_ready)
        is ModelDownloader.Progress.Failed -> "Failed: ${s.message}"
        ModelDownloader.Progress.Idle ->
            if (modelInstalled) stringResource(R.string.step_model_desc_ready)
            else stringResource(R.string.step_model_desc_idle)
    }

    OnboardingCard(
        number = 2,
        title = stringResource(R.string.step_model_title),
        description = description,
        completed = effectivelyInstalled,
    ) {
        when (val s = state) {
            is ModelDownloader.Progress.Downloading,
            ModelDownloader.Progress.Verifying -> {
                LinearProgressIndicator(
                    progress = {
                        if (s is ModelDownloader.Progress.Downloading) s.fraction else 1f
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
                OutlinedButton(onClick = {
                    downloadJob?.cancel()
                    downloadJob = null
                    progress.value = ModelDownloader.Progress.Idle
                }) {
                    Text(stringResource(R.string.step_model_cancel))
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!effectivelyInstalled) {
                        Button(onClick = {
                            downloadJob = scope.launch {
                                downloader.download().collect { p ->
                                    progress.value = p
                                    if (p == ModelDownloader.Progress.Done) {
                                        onModelStateChanged()
                                    }
                                }
                            }
                        }) {
                            Text(stringResource(R.string.step_model_download))
                        }
                    } else {
                        FilledTonalButton(onClick = {
                            scope.launch {
                                downloader.delete()
                                onModelStateChanged()
                            }
                            progress.value = ModelDownloader.Progress.Idle
                        }) {
                            Text(stringResource(R.string.step_model_delete))
                        }
                    }
                }
            }
        }
    }
}

private fun openIMESettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun showInputMethodPicker(context: Context) {
    val imm = context.getSystemService(InputMethodManager::class.java)
    imm?.showInputMethodPicker()
}

@Composable
private fun AccessibilityCard(
    completed: Boolean,
    onEnableClicked: () -> Unit,
) {
    val description = if (completed) {
        stringResource(R.string.step_a11y_enabled)
    } else {
        stringResource(R.string.step_a11y_desc)
    }
    OptionalCard(
        title = stringResource(R.string.step_a11y_title),
        description = description,
        completed = completed,
    ) {
        if (!completed) {
            OutlinedButton(onClick = onEnableClicked) {
                Text(stringResource(R.string.step_a11y_enable))
            }
        }
    }
}

@Composable
private fun WatchCompanionCard() {
    val context = LocalContext.current
    // null = still loading, true = paired, false = no watch detected.
    var reachable by remember { mutableStateOf<Boolean?>(null) }

    // Capability lookups are network-y enough on cold start that a brief
    // "Loading…" beats showing "No watch detected" while we wait.
    LaunchedEffect(Unit) {
        reachable = WatchPresence.isWatchReachable(context)
    }

    val description = when (reachable) {
        null -> stringResource(R.string.step_watch_desc)
        true -> stringResource(R.string.step_watch_status_connected)
        false -> {
            // Show both the marketing blurb AND the negative status, so
            // a user who hasn't installed yet still understands what
            // this card is for.
            stringResource(R.string.step_watch_desc) +
                "\n\n" +
                stringResource(R.string.step_watch_status_unknown)
        }
    }
    OptionalCard(
        title = stringResource(R.string.step_watch_title),
        description = description,
        completed = reachable == true,
    ) {
        // No action button: the watch APK is sideloaded today; once
        // there's a Play Store listing we can hang a deep-link off this
        // slot.
    }
}

/**
 * Card variant for optional onboarding steps — same visuals as
 * [OnboardingCard] but without a step number badge. We render a check
 * mark when `completed` and a soft outline circle when not, so the
 * card still reads as part of the same flow without making the user
 * feel they're behind on a required step.
 */
@Composable
private fun OptionalCard(
    title: String,
    description: String,
    completed: Boolean,
    content: @Composable () -> Unit,
) {
    val containerColor =
        if (completed) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OptionalBadge(completed = completed)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun OptionalBadge(completed: Boolean) {
    Surface(
        modifier = Modifier.size(28.dp),
        shape = CircleShape,
        color = if (completed) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (completed) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (completed) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            // When not completed we leave the badge empty — these are
            // optional steps, not numbered ones, so we don't want to
            // imply an ordering the way the main StepBadge does.
        }
    }
}
