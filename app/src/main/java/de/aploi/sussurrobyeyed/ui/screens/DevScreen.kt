package de.aploi.sussurrobyeyed.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.aploi.sussurrobyeyed.R
import de.aploi.sussurrobyeyed.audio.AudioRecorder
import de.aploi.sussurrobyeyed.audio.SilenceTrimmer
import de.aploi.sussurrobyeyed.data.Settings
import de.aploi.sussurrobyeyed.model.ModelDownloader
import de.aploi.sussurrobyeyed.ui.components.CapsuleState
import de.aploi.sussurrobyeyed.ui.components.SectionCard
import de.aploi.sussurrobyeyed.ui.components.SussurroCapsule
import de.aploi.sussurrobyeyed.ui.components.Waveform
import de.aploi.sussurrobyeyed.whisper.WhisperEngine
import de.aploi.sussurrobyeyed.whisper.WhisperLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Developer-only debug surface (gated by `BuildConfig.DEBUG` at the call site).
 *
 * It mirrors what the IME does end-to-end — record → silence-trim → whisper —
 * but instead of committing the transcript into a host app, it dumps every
 * intermediate buffer and timing onto the screen so we can verify nothing
 * important is being thrown away by the trimmer or the quantised model.
 *
 * The screen owns its own [AudioRecorder] and [WhisperEngine] instance for
 * isolation; the IME's engine (if any) is left alone so we don't fight over
 * the same JNI context.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevScreen(
    settings: Settings,
    downloader: ModelDownloader,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { AudioRecorder() }
    val rms by recorder.rms.collectAsState()

    var capsuleState by remember { mutableStateOf(CapsuleState.Idle) }
    var engineState by remember { mutableStateOf<EngineState>(EngineState.Loading) }
    var lastResult by remember { mutableStateOf<DevResult?>(null) }

    val hasMic = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    // Load the engine when the screen is shown; release it on exit.
    LaunchedEffect(downloader.modelFile.absolutePath) {
        engineState = EngineState.Loading
        val started = System.currentTimeMillis()
        try {
            val libDir = context.applicationInfo.nativeLibraryDir
            val engine = withContext(Dispatchers.IO) {
                WhisperEngine.load(downloader.modelFile, libDir)
            }
            val loadMs = System.currentTimeMillis() - started
            engineState = EngineState.Ready(engine, loadMs)
        } catch (t: Throwable) {
            engineState = EngineState.Error(t.message ?: "unknown")
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            // Release the whisper context off the event loop. We're tearing
            // down so a brief block is fine.
            val current = engineState
            if (current is EngineState.Ready) {
                runCatching { runBlocking { current.engine.release() } }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.dev_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.dev_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            // Capsule + status banner.
            SussurroCapsule(
                state = capsuleState,
                rms = rms,
                onClick = {
                    val ready = engineState as? EngineState.Ready ?: return@SussurroCapsule
                    if (!hasMic) return@SussurroCapsule
                    when (capsuleState) {
                        CapsuleState.Idle -> {
                            recorder.start(scope)
                            capsuleState = CapsuleState.Listening
                        }
                        CapsuleState.Listening -> {
                            capsuleState = CapsuleState.Transcribing
                            scope.launch {
                                val capture = recorder.stopAndCollect()
                                if (capture.isEmpty()) {
                                    capsuleState = CapsuleState.Idle
                                    return@launch
                                }
                                val trim = SilenceTrimmer.trim(capture, AudioRecorder.SAMPLE_RATE)
                                val toDecode = if (trim.trimmed.size >= AudioRecorder.SAMPLE_RATE / 5) {
                                    trim.trimmed
                                } else {
                                    capture
                                }
                                val transcribeStart = System.currentTimeMillis()
                                val threadCount = WhisperEngine.defaultThreadCount()
                                val text = runCatching {
                                    ready.engine.transcribe(
                                        audio = toDecode,
                                        language = settings.language.takeIf { it != Settings.LANGUAGE_AUTO },
                                        translate = false,
                                        threads = threadCount,
                                    )
                                }.getOrElse { "<error: ${it.message}>" }
                                val transcribeMs = System.currentTimeMillis() - transcribeStart
                                val timings = runCatching { ready.engine.lastTimings() }.getOrNull()

                                lastResult = DevResult(
                                    capture = capture,
                                    trim = trim,
                                    transcript = text,
                                    transcribeMs = transcribeMs,
                                    engineLoadMs = ready.loadMs,
                                    threadCount = threadCount,
                                    usedFallback = toDecode === capture,
                                    sampleRate = AudioRecorder.SAMPLE_RATE,
                                    timings = timings,
                                )
                                capsuleState = CapsuleState.Idle
                            }
                        }
                        CapsuleState.Transcribing -> Unit
                    }
                },
            )

            EngineBanner(
                state = engineState,
                hasMicPermission = hasMic,
            )

            val result = lastResult
            if (result == null) {
                Text(
                    text = stringResource(R.string.dev_no_capture),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ResultBody(result = result)
            }

            // Always-visible environment block — useful even before the user
            // has run a transcription, since it tells us which CPU SIMD paths
            // whisper.cpp compiled in.
            EnvironmentBlock()

            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ---------- result block ---------- */

@Composable
private fun ResultBody(result: DevResult) {
    SectionCard(title = stringResource(R.string.dev_section_waveform)) {
        Waveform(
            audio = result.capture,
            trim = result.trim,
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)),
        )
        WaveformLegend()
    }

    SectionCard(title = stringResource(R.string.dev_section_stats)) {
        StatsTable(result)
    }

    SectionCard(title = stringResource(R.string.dev_section_whisper_timings)) {
        WhisperTimingsTable(result)
    }

    SectionCard(title = stringResource(R.string.dev_section_transcript)) {
        Text(
            text = if (result.transcript.isBlank()) "(empty)" else result.transcript,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun WhisperTimingsTable(result: DevResult) {
    val t = result.timings
    if (t == null) {
        Text(
            text = stringResource(R.string.dev_timings_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val totalCovered = t.totalMs
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Encode tends to dominate; show pct of wall-clock so it's easy to
        // tell whether encoder or decoder is the bottleneck.
        StatRow(stringResource(R.string.dev_timings_encode), formatMs(t.encodeMs, result.transcribeMs))
        StatRow(stringResource(R.string.dev_timings_decode), formatMs(t.decodeMs, result.transcribeMs))
        StatRow(stringResource(R.string.dev_timings_batchd), formatMs(t.batchdMs, result.transcribeMs))
        StatRow(stringResource(R.string.dev_timings_sample), formatMs(t.sampleMs, result.transcribeMs))
        StatRow(stringResource(R.string.dev_timings_prompt), formatMs(t.promptMs, result.transcribeMs))
        StatRow(
            label = "Sum",
            value = "%.0f ms (%.0f%% accounted)".format(
                totalCovered,
                if (result.transcribeMs > 0) totalCovered / result.transcribeMs * 100f else 0f,
            ),
        )
    }
}

private fun formatMs(ms: Float, walltimeMs: Long): String {
    val pct = if (walltimeMs > 0) ms / walltimeMs * 100f else 0f
    return "%.0f ms · %.0f%%".format(ms, pct)
}

/* ---------- environment block ---------- */

@Composable
private fun EnvironmentBlock() {
    val systemInfo = remember {
        runCatching { WhisperLib.nativeSystemInfo() }
            .getOrDefault("(unavailable)")
    }
    SectionCard(title = stringResource(R.string.dev_section_environment)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatRow(
                label = stringResource(R.string.dev_env_device),
                value = "${Build.MANUFACTURER} ${Build.MODEL}",
            )
            StatRow(
                label = stringResource(R.string.dev_env_soc),
                value = if (Build.VERSION.SDK_INT >= 31) {
                    "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
                } else {
                    Build.HARDWARE
                },
            )
            StatRow(
                label = stringResource(R.string.dev_env_abi),
                value = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            )
            StatRow(
                label = stringResource(R.string.dev_env_android),
                value = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = stringResource(R.string.dev_env_whisper),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = systemInfo,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WaveformLegend() {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(color = cs.primary, label = stringResource(R.string.dev_legend_kept))
        LegendItem(
            color = cs.onSurfaceVariant.copy(alpha = 0.45f),
            label = stringResource(R.string.dev_legend_trimmed),
        )
        LegendItem(
            color = cs.tertiary.copy(alpha = 0.7f),
            label = stringResource(R.string.dev_legend_threshold),
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatsTable(result: DevResult) {
    val sr = result.sampleRate.toFloat()
    val total = result.capture.size
    val kept = result.trim.trimmed.size
    val lead = result.trim.leadingSamplesCut
    val trail = result.trim.trailingSamplesCut
    val totalSec = total / sr
    val keptSec = kept / sr
    val leadSec = lead / sr
    val trailSec = trail / sr

    var peak = 0f
    var sumSq = 0.0
    for (s in result.capture) {
        val a = if (s < 0f) -s else s
        if (a > peak) peak = a
        sumSq += s.toDouble() * s
    }
    val overallRms = if (total > 0) kotlin.math.sqrt(sumSq / total).toFloat() else 0f
    val rtf = if (keptSec > 0f) result.transcribeMs / 1000f / keptSec else 0f

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatRow(stringResource(R.string.dev_stats_duration), "%.2f s (%d samples)".format(totalSec, total))
        StatRow(
            stringResource(R.string.dev_stats_after_trim),
            if (result.usedFallback) {
                "fallback: %.2f s (%d samples)".format(totalSec, total)
            } else {
                "%.2f s (%d samples)".format(keptSec, kept)
            },
        )
        StatRow(
            stringResource(R.string.dev_stats_leading_cut),
            "%d ms (%d samples)".format((leadSec * 1000).toInt(), lead),
        )
        StatRow(
            stringResource(R.string.dev_stats_trailing_cut),
            "%d ms (%d samples)".format((trailSec * 1000).toInt(), trail),
        )
        StatRow(stringResource(R.string.dev_stats_threshold), "%.4f".format(result.trim.rmsThreshold))
        StatRow(stringResource(R.string.dev_stats_peak), "%.3f".format(peak))
        StatRow(stringResource(R.string.dev_stats_rms), "%.4f".format(overallRms))
        StatRow(stringResource(R.string.dev_stats_engine_load), "${result.engineLoadMs} ms")
        StatRow(stringResource(R.string.dev_stats_transcribe), "${result.transcribeMs} ms")
        StatRow(stringResource(R.string.dev_stats_rtf), "%.2f×".format(rtf))
        StatRow(stringResource(R.string.dev_stats_threads), result.threadCount.toString())
        StatRow(
            stringResource(R.string.dev_stats_samples),
            "rate ${result.sampleRate} Hz · window ${result.trim.windowSizeSamples} sa",
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/* ---------- engine banner ---------- */

@Composable
private fun EngineBanner(state: EngineState, hasMicPermission: Boolean) {
    when (state) {
        EngineState.Loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = stringResource(R.string.dev_engine_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is EngineState.Error -> Text(
            text = stringResource(R.string.dev_engine_error, state.message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        is EngineState.Ready -> if (!hasMicPermission) {
            Text(
                text = stringResource(R.string.capsule_state_no_permission),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ---------- internal state ---------- */

private sealed interface EngineState {
    data object Loading : EngineState
    data class Ready(val engine: WhisperEngine, val loadMs: Long) : EngineState
    data class Error(val message: String) : EngineState
}

private data class DevResult(
    val capture: FloatArray,
    val trim: SilenceTrimmer.Result,
    val transcript: String,
    val transcribeMs: Long,
    val engineLoadMs: Long,
    val threadCount: Int,
    val usedFallback: Boolean,
    val sampleRate: Int,
    val timings: WhisperEngine.Timings?,
) {
    // Suppress the "data class with arrays" equals/hashCode warning — we only
    // compare by identity in Compose.
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}
