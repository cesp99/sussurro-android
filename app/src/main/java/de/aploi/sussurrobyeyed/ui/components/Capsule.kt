package de.aploi.sussurrobyeyed.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The three visual states of the Sussurro capsule.
 *
 * They mirror the three screenshots from the desktop overlay:
 *  - [Idle]: 7 softly pulsing dots
 *  - [Listening]: 7 vertical bars driven by microphone RMS
 *  - [Transcribing]: the word "transcribing" with a shimmer sweep
 */
enum class CapsuleState { Idle, Listening, Transcribing }

private const val ITEM_COUNT = 7

/**
 * The Sussurro capsule, sized identically to the desktop overlay (220 x 52 dp).
 *
 * In a phone keyboard the capsule sits in the middle of the input area; tapping
 * it toggles between Idle ↔ Listening (and the host triggers Transcribing once
 * recording ends). The capsule itself never owns state; pass [state] from above
 * and [rms] when [state] == [CapsuleState.Listening].
 */
@Composable
fun SussurroCapsule(
    state: CapsuleState,
    rms: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 220.dp,
    height: Dp = 52.dp,
    label: String? = null,
) {
    val cs = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(percent = 50), clip = false)
            .clip(RoundedCornerShape(percent = 50))
            .background(cs.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = cs.outlineVariant,
                shape = RoundedCornerShape(percent = 50),
            )
            .pointerInput(onClick) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                // Sized scale+fade for a softer hand-off; the incoming child
                // grows in slightly while the outgoing one fades + shrinks. The
                // outgoing animation starts immediately so the bars don't
                // "freeze" while the shimmer fades in.
                val inSpec = tween<Float>(durationMillis = 280, easing = FastOutSlowInEasing)
                val outSpec = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)
                (fadeIn(animationSpec = inSpec) + scaleIn(initialScale = 0.92f, animationSpec = inSpec))
                    .togetherWith(fadeOut(animationSpec = outSpec) + scaleOut(targetScale = 0.92f, animationSpec = outSpec))
            },
            label = "capsule-state",
        ) { s ->
            when (s) {
                CapsuleState.Idle -> IdleDots(color = cs.onSurface, modifier = Modifier.fillMaxSize())
                CapsuleState.Listening -> RecordingBars(color = cs.onSurface, rms = rms, modifier = Modifier.fillMaxSize())
                CapsuleState.Transcribing -> TranscribingText(
                    text = label ?: "transcribing",
                    color = cs.onSurface,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/* ---------- Idle dots ---------- */

@Composable
private fun IdleDots(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "idle-dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Canvas(modifier = modifier) {
        val radius = 3.dp.toPx()
        val spacing = 10.dp.toPx()
        val total = (ITEM_COUNT - 1) * spacing
        val startX = (size.width - total) / 2f
        val cy = size.height / 2f

        for (i in 0 until ITEM_COUNT) {
            val phi = phase + i * (2f * PI / ITEM_COUNT).toFloat()
            val s = sin(phi)
            // The capsule reference uses 0.35 + 0.65 * s^2, which keeps the
            // pulse warm and never fully blanks a dot.
            val alpha = 0.35f + 0.65f * (s * s)
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                center = Offset(startX + i * spacing, cy),
            )
        }
    }
}

/* ---------- Recording bars ---------- */

@Composable
private fun RecordingBars(color: Color, rms: Float, modifier: Modifier = Modifier) {
    // We keep a tiny ring buffer of the last N RMS values, so each bar
    // represents a slightly older sample — gives the bars a "waterfall" feel.
    val ring = remember { FloatArray(ITEM_COUNT) }
    var head by remember { mutableIntStateOf(0) }

    LaunchedEffect(rms) {
        ring[head] = rms
        head = (head + 1) % ITEM_COUNT
    }

    // Smooth the targets a touch — without this the bars look jittery on most
    // mics. Same easing as the desktop overlay (0.7 / 0.3 mix).
    val smoothed = remember { FloatArray(ITEM_COUNT) }
    val transition = rememberInfiniteTransition(label = "smooth-tick")
    val tick by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tick",
    )

    Canvas(modifier = modifier) {
        // Hook the tick read so Compose redraws every animation frame.
        @Suppress("UNUSED_EXPRESSION") tick

        val barWidth = 5.dp.toPx()
        val barRadius = 2.5.dp.toPx()
        val spacing = 8.dp.toPx()
        val minHeight = 4.dp.toPx()
        val maxHeight = 40.dp.toPx()
        val total = (ITEM_COUNT - 1) * spacing
        val startX = (size.width - total) / 2f
        val cy = size.height / 2f

        for (i in 0 until ITEM_COUNT) {
            val idx = (head + i) % ITEM_COUNT
            val norm = min(1f, ring[idx] / 0.08f) // RMS_SCALE from the desktop overlay
            val target = minHeight + norm * (maxHeight - minHeight)
            smoothed[i] = smoothed[i] * 0.7f + target * 0.3f

            val h = max(minHeight, smoothed[i])
            val cx = startX + i * spacing
            val x = cx - barWidth / 2f
            val y = cy - h / 2f

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barRadius, barRadius),
            )
        }
    }
}

/* ---------- Transcribing text ---------- */

@Composable
private fun TranscribingText(text: String, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-phase",
    )

    val brush = remember(phase, color) {
        // A 0..1 sweep, mapped to a horizontal gradient that travels across the
        // text. The translate range is set generously so the shimmer never
        // visibly snaps.
        val width = 600f
        val x = -200f + width * 1.6f * phase
        Brush.linearGradient(
            colors = listOf(
                color.copy(alpha = 0.55f),
                color,
                color.copy(alpha = 0.55f),
            ),
            start = Offset(x - 80f, 0f),
            end = Offset(x + 80f, 0f),
        )
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color.Unspecified, // brush wins
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium.copy(
                brush = brush,
            ),
        )
    }
}
