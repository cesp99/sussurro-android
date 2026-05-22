package de.aploi.sussurrobyeyed.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import de.aploi.sussurrobyeyed.BuildConfig
import de.aploi.sussurrobyeyed.R
import de.aploi.sussurrobyeyed.data.Settings
import de.aploi.sussurrobyeyed.data.SettingsStore
import de.aploi.sussurrobyeyed.data.ThemeMode
import de.aploi.sussurrobyeyed.inject.TextInjector
import de.aploi.sussurrobyeyed.model.ModelDownloader
import de.aploi.sussurrobyeyed.model.WhisperModel
import de.aploi.sussurrobyeyed.ui.components.LanguagePicker
import de.aploi.sussurrobyeyed.ui.components.SectionCard
import de.aploi.sussurrobyeyed.wear.LastWatchTranscript
import de.aploi.sussurrobyeyed.wear.WatchPresence
import de.aploi.sussurrobyeyed.whisper.WhisperLib
import kotlinx.coroutines.launch

/**
 * Sussurro's only settings screen.
 *
 * Sections mirror the desktop app:
 *  - Speech Recognition (Whisper)
 *  - Transcription Language
 *  - Output (lowercase + trim trailing punctuation)
 *  - Appearance (System / Light / Dark)
 *  - About
 *
 * Persistence is delegated to [SettingsStore]; each control writes through a
 * coroutine launched on the screen's scope.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    settings: Settings,
    store: SettingsStore,
    modelInstalled: Boolean,
    downloader: ModelDownloader,
    onMissingModel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // Speech Recognition
            SectionCard(
                title = stringResource(R.string.settings_section_speech),
                icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
            ) {
                SpeechRow(
                    modelInstalled = modelInstalled,
                    onDownloadNow = onMissingModel,
                    onDelete = {
                        scope.launch { downloader.delete() }
                        onMissingModel()
                    },
                )
            }

            // Language
            SectionCard(
                title = stringResource(R.string.settings_section_language),
                icon = { Icon(Icons.Filled.Language, contentDescription = null) },
            ) {
                LanguagePicker(
                    currentCode = settings.language,
                    onSelect = { scope.launch { store.setLanguage(it) } },
                )
            }

            // Output
            SectionCard(title = stringResource(R.string.settings_section_output)) {
                ToggleRow(
                    label = stringResource(R.string.settings_lowercase_label),
                    description = stringResource(R.string.settings_lowercase_desc),
                    checked = settings.lowercaseOutput,
                    onCheckedChange = { scope.launch { store.setLowercaseOutput(it) } },
                )
                HorizontalDivider()
                ToggleRow(
                    label = stringResource(R.string.settings_trim_label),
                    description = stringResource(R.string.settings_trim_desc),
                    checked = settings.trimTrailingPunctuation,
                    onCheckedChange = { scope.launch { store.setTrimTrailingPunctuation(it) } },
                )
            }

            // Appearance
            SectionCard(
                title = stringResource(R.string.settings_section_appearance),
                icon = { Icon(Icons.Filled.Brightness6, contentDescription = null) },
            ) {
                ThemeSelector(
                    current = settings.themeMode,
                    onSelect = { scope.launch { store.setThemeMode(it) } },
                )
            }

            // Watch companion: pairing + accessibility-fallback status, and
            // the most recent transcript so the user can confirm what their
            // last watch dictation became. All read-only — config lives in
            // system settings and onboarding.
            SectionCard(
                title = stringResource(R.string.settings_section_watch),
                icon = { Icon(Icons.Filled.Watch, contentDescription = null) },
            ) {
                WatchCompanionStatus()
            }

            // About
            SectionCard(title = stringResource(R.string.settings_section_about)) {
                AboutBlock()
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WatchCompanionStatus() {
    val context = LocalContext.current
    val lastTranscript by LastWatchTranscript.value.collectAsState()

    // Capability lookup runs in the background; null = pending. We
    // intentionally don't re-poll on every recomposition; recomposing the
    // settings screen (e.g. toggling a theme) shouldn't churn the BT layer.
    var reachable by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        reachable = WatchPresence.isWatchReachable(context)
    }
    val accessibilityEnabled = remember { TextInjector.isAccessibilityEnabled(context) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusRow(
            label = stringResource(R.string.settings_watch_status_label),
            value = when (reachable) {
                null -> "…"
                true -> stringResource(R.string.settings_watch_status_paired)
                false -> stringResource(R.string.settings_watch_status_unpaired)
            },
        )
        HorizontalDivider()
        StatusRow(
            label = stringResource(R.string.settings_watch_a11y_label),
            value = if (accessibilityEnabled) {
                stringResource(R.string.settings_watch_a11y_on)
            } else {
                stringResource(R.string.settings_watch_a11y_off)
            },
        )
        HorizontalDivider()
        StatusRow(
            label = stringResource(R.string.settings_watch_last_transcript_label),
            value = lastTranscript ?: stringResource(R.string.settings_watch_last_transcript_empty),
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpeechRow(
    modelInstalled: Boolean,
    onDownloadNow: () -> Unit,
    onDelete: () -> Unit,
) {
    val (label, sublabel) = if (modelInstalled) {
        WhisperModel.name to "installed · 488 MB"
    } else {
        WhisperModel.name to "not installed"
    }

    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = sublabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (modelInstalled) {
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.step_model_delete))
            }
        } else {
            OutlinedButton(onClick = onDownloadNow) {
                Text(stringResource(R.string.step_model_download))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.step_model_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel")
                }
            },
            title = { Text(stringResource(R.string.step_model_delete)) },
            text = { Text("Sussurro will need to re-download the model the next time you use it.") },
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ThemeSelector(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val modes = listOf(
        ThemeMode.System to stringResource(R.string.settings_theme_system),
        ThemeMode.Light to stringResource(R.string.settings_theme_light),
        ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
    ) {
        modes.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = current == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun AboutBlock() {
    var showInfo by remember { mutableStateOf(false) }
    var systemInfo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showInfo) {
        if (showInfo && systemInfo == null) {
            systemInfo = runCatching { WhisperLib.nativeSystemInfo() }.getOrNull()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.about_app_name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.about_app_by),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_app_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.about_app_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        TextButton(onClick = { showInfo = !showInfo }) {
            Text(if (showInfo) "Hide whisper.cpp info" else "Show whisper.cpp info")
        }
        if (showInfo) {
            Text(
                text = systemInfo ?: "…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
