package de.aploi.sussurrobyeyed.wear.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.wear.compose.material.Text
import de.aploi.sussurrobyeyed.shared.SessionState
import de.aploi.sussurrobyeyed.wear.R
import de.aploi.sussurrobyeyed.wear.audio.WatchAudioRecorder
import de.aploi.sussurrobyeyed.wear.session.WatchSessionController
import de.aploi.sussurrobyeyed.wear.ui.theme.WatchColors
import kotlinx.coroutines.delay

/**
 * Sussurro's full-screen watch UI.
 *
 * Layout (top → bottom):
 *  - Status text near the top, just below the system clock so the system
 *    chrome doesn't fight us. Always rendered, fades subtly between states.
 *  - Centered on the lower third: the [AuroraGlow] taking up the bottom
 *    half of the screen, with the bottom-edge ambient glow rendered even
 *    when idle.
 *
 * Interactions:
 *  - Tap anywhere on the screen to toggle:
 *      Idle → Recording (start session)
 *      Recording → Transcribing (stop & let the phone transcribe)
 *      Transcribing → ignored (auto-returns to Idle once committed)
 *      Committed/Error → tap to clear and return to Idle.
 *  - If RECORD_AUDIO isn't granted, we hand the user off to [onRequestMic].
 */
@Composable
fun WatchScreen(
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onCancelSession: () -> Unit,
) {
    val sessionState by WatchSessionController.state.collectAsState()
    val rms by WatchSessionController.recorder.rms.collectAsState()
    val recorderState by WatchSessionController.recorder.state.collectAsState()
    val reason by WatchSessionController.reason.collectAsState()
    val lastTranscript by WatchSessionController.lastTranscript.collectAsState()

    val statusText = when {
        !micGranted -> stringResource(R.string.state_no_permission)
        sessionState == SessionState.Recording -> stringResource(R.string.state_listening)
        sessionState == SessionState.Transcribing -> stringResource(R.string.state_transcribing)
        sessionState == SessionState.Committed -> stringResource(R.string.state_committed)
        sessionState == SessionState.Error -> reason ?: stringResource(R.string.state_error)
        else -> stringResource(R.string.state_idle)
    }

    // Intensity drives how prominent the aurora curtains look. The aurora
    // is always at least faintly visible (the user asked for "always on,
    // gentle drift" at idle), so even SessionState.Idle gets a non-zero
    // baseline instead of going fully dark.
    val intensity = when (sessionState) {
        SessionState.Recording -> 1f
        SessionState.Transcribing -> 0.75f
        SessionState.Committed -> 0.55f
        SessionState.Error -> 0.40f
        SessionState.Idle -> 0.40f
    }

    // Only feed real mic amplitude into the aurora while the recorder is
    // actively capturing; otherwise the curtains would keep "reacting" to
    // a stale RMS value after stop. During transcribing the aurora gets
    // its own synthetic pulse from the composable side.
    val displayAmplitude = if (recorderState == WatchAudioRecorder.State.Recording) rms else 0f

    // Transient transcript echo: once we've committed, peek at the first
    // few words for a beat so the user sees their dictation actually
    // made it. Auto-clears after 1.5 s; state auto-fades back to Idle
    // independently a beat later (controller-managed), so we don't have
    // to coordinate with that.
    var transcriptOverlayVisible by remember { mutableStateOf(false) }
    LaunchedEffect(lastTranscript, sessionState) {
        if (sessionState == SessionState.Committed && !lastTranscript.isNullOrEmpty()) {
            transcriptOverlayVisible = true
            delay(1_500)
            transcriptOverlayVisible = false
        } else if (sessionState != SessionState.Committed) {
            transcriptOverlayVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WatchColors.Background)
            .pointerInput(micGranted, sessionState) {
                detectTapGestures(
                    onTap = {
                        if (!micGranted) {
                            onRequestMic()
                            return@detectTapGestures
                        }
                        when (sessionState) {
                            SessionState.Idle -> onStartSession()
                            SessionState.Recording -> onStopSession()
                            SessionState.Transcribing -> { /* wait it out */ }
                            SessionState.Committed,
                            SessionState.Error -> onCancelSession()
                        }
                    },
                    // Long-press at any time aborts the session so the user
                    // isn't stuck if something hangs.
                    onLongPress = { onCancelSession() },
                )
            },
    ) {
        // Aurora fills the whole canvas; its rendering already constrains
        // the curtains to the bottom half of the watch face.
        AuroraGlow(
            amplitude = displayAmplitude,
            intensity = intensity,
            transcribing = sessionState == SessionState.Transcribing,
            modifier = Modifier.fillMaxSize(),
        )

        // Status overlay. Sits up top so the bottom aurora owns the rest
        // of the visual real estate.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 36.dp, bottom = 24.dp)
                .zIndex(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = statusText,
                color = WatchColors.OnBackground.copy(alpha = 0.92f),
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            )

            Spacer(Modifier.height(6.dp))

            // Surface the controller's reason as the sub-label whenever
            // it's set, so e.g. the "max length reached" auto-stop has
            // somewhere to appear in non-Error states. Falls back to the
            // ambient "tap to …" hint when reason is null.
            val secondary = reason?.takeIf { sessionState != SessionState.Error }
                ?: when (sessionState) {
                    SessionState.Idle -> "tap to record"
                    SessionState.Recording -> "tap to send"
                    SessionState.Transcribing -> "phone is listening"
                    SessionState.Committed -> "tap for another"
                    SessionState.Error -> "tap to retry"
                }
            Text(
                text = secondary,
                color = WatchColors.OnBackgroundMuted,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            // Transient transcript echo. Lives between the status text and
            // the waveform (a third of the way down from the top crown),
            // so it doesn't fight the bottom waveform for visual real
            // estate.
            AnimatedVisibility(
                visible = transcriptOverlayVisible,
                enter = fadeIn(animationSpec = tween(durationMillis = 200)),
                exit = fadeOut(animationSpec = tween(durationMillis = 350)),
            ) {
                val text = lastTranscript.orEmpty()
                Text(
                    // Cap so even a long line doesn't wrap onto the waveform.
                    text = if (text.length > 40) text.take(40).trimEnd() + "…" else text,
                    color = WatchColors.OnBackground.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                )
            }
        }
    }

    // The rest of the watch face stays intentionally empty — the design's
    // intent is a near-empty dial whose meaning lives in the glowing
    // aurora at the bottom.
}
