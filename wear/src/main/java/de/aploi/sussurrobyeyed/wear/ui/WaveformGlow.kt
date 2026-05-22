package de.aploi.sussurrobyeyed.wear.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import de.aploi.sussurrobyeyed.wear.ui.theme.WatchColors
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A bottom-anchored glowing waveform.
 *
 * Visual rules:
 *  - Renders a gentle white glow along the curved edge of the screen even when
 *    [intensity] is 0, so the watch never feels "off". Tracks the bottom arc
 *    of a round display.
 *  - When [intensity] grows, columns of bars rise from the bottom. Each bar's
 *    height is driven by the most recent [amplitude] sample (the watch
 *    AudioRecorder's RMS), with a per-column phase offset and tiny LFO so the
 *    field never looks static.
 *  - A soft outer glow is painted under the bars by stroking the same path
 *    with a wider, lower-alpha colour, plus an additive glow blob along the
 *    bottom — that's the "bluish halo" from the reference screenshot.
 *
 * @param amplitude latest mic RMS in [0,1].
 * @param intensity overall presence of the waveform in [0,1]. 0 ≈ ambient
 *   idle glow only, 1 ≈ fully grown bars during recording.
 * @param transcribing if true, an additional sweeping shimmer is layered on
 *   top of the bars to communicate "the phone is working".
 */
@Composable
fun WaveformGlow(
    amplitude: Float,
    intensity: Float,
    transcribing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = WatchColors.OnBackground,
    glowColor: Color = WatchColors.Glow,
) {
    // 24 bars across the bottom is dense enough to look smooth but cheap
    // enough that the watch GPU can redraw at the per-frame infinite ticker
    // we install below.
    val barCount = 24

    // Ring buffer of recent amplitudes — gives every bar its own "history" so
    // they don't all bounce in unison. Treated like a scroll: we shift and
    // append the latest amplitude on every recomposition.
    val ring = remember { FloatArray(barCount) }
    var head by remember { mutableIntStateOf(0) }

    LaunchedEffect(amplitude) {
        ring[head] = amplitude
        head = (head + 1) % barCount
    }

    // Ease the intensity in/out so the bars don't snap when we transition
    // states. ~280 ms gives a soft "growing" effect without dragging.
    val animatedIntensity by animateFloatAsState(
        targetValue = intensity.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 280, easing = LinearEasing),
        label = "intensity",
    )

    // Idle pulse — even at intensity=0 the bottom should breathe. Phase comes
    // from a clock tick that recomposes us at ~60 Hz.
    val transition = rememberInfiniteTransition(label = "wave-tick")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave-phase",
    )

    val shimmer by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer",
    )

    // Smoothed bar heights so the waveform doesn't look jittery on noisy
    // mics. Same low-pass mix as the phone keyboard's RecordingBars.
    val smoothed = remember { FloatArray(barCount) }

    // Track the previous transcribing flag so we don't allocate a brush every
    // frame; the brush only depends on shimmer + colour + size.
    val transcribingState = remember { mutableStateOf(transcribing) }
    transcribingState.value = transcribing

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        // Approximate the watch face radius from the canvas size.
        val radius = max(w, h) / 2f
        // Centre of the implied circle is below the canvas — the bottom arc
        // we want to highlight is the segment that the watch displays at the
        // bottom of the dial.
        val centerY = h - radius

        // ---- 1. Ambient bottom-edge glow (always visible) ----
        val ambientAlpha = 0.10f + 0.10f * (0.5f + 0.5f * sin(phase).toFloat())
        val ambientPath = Path().apply {
            // A thin arc along the bottom; we approximate with a stroked path
            // that traces the bottom 90 degrees of the watch circle.
            addArc(
                oval = Rect(
                    left = cx - radius,
                    top = centerY - radius,
                    right = cx + radius,
                    bottom = centerY + radius,
                ),
                startAngleDegrees = 30f,
                sweepAngleDegrees = 120f,
            )
        }
        // Wide soft underglow then a tighter brighter line on top — the
        // bottom of the screen reads as "lit from below" the way the
        // reference screenshot does.
        drawPath(
            path = ambientPath,
            color = glowColor.copy(alpha = 0.30f * ambientAlpha + 0.12f),
            style = Stroke(width = 36.dp.toPx()),
        )
        drawPath(
            path = ambientPath,
            color = color.copy(alpha = 0.55f * ambientAlpha + 0.18f),
            style = Stroke(width = 14.dp.toPx()),
        )
        drawPath(
            path = ambientPath,
            color = color.copy(alpha = 0.85f),
            style = Stroke(width = 2.dp.toPx()),
        )

        // ---- 2. Active bars when intensity > 0 ----
        if (animatedIntensity > 0.001f) {
            val barWidth = 4.dp.toPx()
            val barGap = 4.dp.toPx()
            val totalWidth = barCount * barWidth + (barCount - 1) * barGap
            val left = cx - totalWidth / 2f
            val baseY = h - 18.dp.toPx() // sits just above the bottom rim
            val maxBarHeight = (h * 0.55f).coerceAtLeast(80.dp.toPx())

            // Clip the bars to the implied round face. Without this, even
            // tapered bars near the screen edge can leak under the bezel
            // on small / strongly-curved watches; the bezel then chops
            // them mid-stroke and the silhouette reads as broken.
            val watchFacePath = Path().apply {
                addOval(
                    Rect(
                        left = cx - radius,
                        top = centerY - radius,
                        right = cx + radius,
                        bottom = centerY + radius,
                    ),
                )
            }

            clipPath(watchFacePath) {
                for (i in 0 until barCount) {
                    val historyIdx = (head + i) % barCount
                    val raw = ring[historyIdx]
                    val norm = min(1f, raw / 0.08f) // RMS_SCALE matches phone capsule
                    // LFO so quiet captures still wave gently rather than freezing.
                    val lfo = 0.5f + 0.5f * sin(phase * 1.5f + i * 0.4f).toFloat()
                    val target = norm * 0.85f + lfo * 0.15f
                    smoothed[i] = smoothed[i] * 0.7f + target * 0.3f

                    // Distance from the centre — bars at the edge of the
                    // screen run into the round bezel, so we taper their
                    // max height following the implied circle. The
                    // clipPath above is the hard guarantee; this taper is
                    // a visual softener so the silhouette curves rather
                    // than stair-stepping.
                    val barCx = left + i * (barWidth + barGap) + barWidth / 2f
                    val dx = barCx - cx
                    val edgeFactor = 1f - (dx * dx) / (radius * radius)
                    val taper = edgeFactor.coerceIn(0.05f, 1f)

                    val height = maxBarHeight * smoothed[i] * animatedIntensity * taper
                    val top = baseY - height

                    val barRect = RoundRect(
                        left = barCx - barWidth / 2f,
                        top = top,
                        right = barCx + barWidth / 2f,
                        bottom = baseY,
                        radiusX = barWidth / 2f,
                        radiusY = barWidth / 2f,
                    )

                    // Fill: vertical white -> bluish gradient so each bar feels
                    // luminescent.
                    val brush = Brush.verticalGradient(
                        colors = listOf(
                            color.copy(alpha = 0.95f),
                            color.copy(alpha = 0.75f),
                            glowColor.copy(alpha = 0.35f),
                        ),
                        startY = top,
                        endY = baseY,
                    )
                    drawPath(
                        path = Path().apply { addRoundRect(barRect) },
                        brush = brush,
                    )

                    // Soft outer glow stroke — small but noticeable.
                    drawPath(
                        path = Path().apply { addRoundRect(barRect) },
                        color = glowColor.copy(alpha = 0.40f * animatedIntensity),
                        style = Stroke(width = 6.dp.toPx()),
                    )
                }
            }
        }

        // ---- 3. Transcribing shimmer overlay ----
        if (transcribingState.value) {
            // A bright, narrow gradient travels left-to-right across the
            // ambient arc, signalling "thinking".
            val sweepX = w * shimmer
            val sweepBrush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    color.copy(alpha = 0.95f),
                    Color.Transparent,
                ),
                start = Offset(sweepX - w * 0.12f, 0f),
                end = Offset(sweepX + w * 0.12f, h),
            )
            drawPath(
                path = ambientPath,
                brush = sweepBrush,
                style = Stroke(width = 6.dp.toPx()),
            )
        }
    }
}
