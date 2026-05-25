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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Aurora-borealis style glow that anchors the bottom half of the watch face.
 *
 * Visual rules:
 *  - Several wavy "curtains" of light flow across the bottom half of the
 *    canvas. Each curtain has a bright white ridge at its top edge that
 *    fades down through pale lilac into a deeper violet wash before
 *    melting into transparent — the same way a real aurora's tendrils
 *    fade from white-hot at the crest into dim purple at the horizon.
 *  - The curtains never sit still. Three independent low-frequency
 *    phases (slow / medium / fast) drive different bands, so the whole
 *    field undulates organically and never repeats on screen.
 *  - Microphone amplitude lifts the curtains higher, deepens their
 *    wobble and brightens the white edge — this is the "reacts to
 *    speech" cue while recording. Without mic input the curtains still
 *    drift gently from the LFO phases.
 *  - During transcribing a small synthetic amplitude keeps the curtains
 *    breathing, and a soft shimmer sweeps across the bottom half to
 *    communicate "the phone is thinking".
 *
 * @param amplitude raw mic RMS in [0, 1] (treated against the 0.08 scale
 *   the phone capsule uses, so a normal-volume voice fills the field).
 * @param intensity overall presence in [0, 1]. 0 is the calm "always
 *   visible" idle baseline; 1 is "fully lit" during an active session.
 * @param transcribing toggles the shimmer + synthetic motion overlay.
 */
@Composable
fun AuroraGlow(
    amplitude: Float,
    intensity: Float,
    transcribing: Boolean,
    modifier: Modifier = Modifier,
) {
    // Three slow LFO phases on different periods. Picking values that
    // aren't simple integer ratios of each other keeps the resulting
    // motion from quantising into an obvious loop.
    val transition = rememberInfiniteTransition(label = "aurora-tick")
    val phaseSlow by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11_300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase-slow",
    )
    val phaseMed by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7_100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase-med",
    )
    val phaseFast by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase-fast",
    )

    // Sweep that travels left-to-right while the phone is transcribing.
    val shimmer by transition.animateFloat(
        initialValue = -0.25f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer",
    )

    // Ease overall presence in / out so the curtains don't snap on session
    // transitions.
    val animatedIntensity by animateFloatAsState(
        targetValue = intensity.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 480, easing = LinearEasing),
        label = "intensity",
    )

    // Light EMA on the mic amplitude so the curtains don't jitter on noisy
    // mics, but stay responsive enough to feel like the user's voice is
    // pushing them.
    val smoothedAmplitude = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(amplitude) {
        smoothedAmplitude.floatValue =
            smoothedAmplitude.floatValue * 0.55f + amplitude.coerceIn(0f, 1f) * 0.45f
    }
    // Match the phone keyboard's capsule mapping: an RMS around 0.08
    // counts as "full".
    val ampNorm = (smoothedAmplitude.floatValue / 0.08f).coerceIn(0f, 1f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        // Assume the round face fills the canvas (true on Wear round
        // displays) and clip everything inside it. This way the curtains
        // taper into the bezel cleanly rather than getting chopped flat
        // at the corners.
        val radius = min(w, h) / 2f
        val faceCenterY = h / 2f
        val watchFace = Path().apply {
            addOval(
                Rect(
                    left = cx - radius,
                    top = faceCenterY - radius,
                    right = cx + radius,
                    bottom = faceCenterY + radius,
                ),
            )
        }

        // When transcribing we still want the curtains to look alive even
        // though there's no mic feeding amplitude. A small synthetic LFO
        // wave gives them a gentle "thinking" pulse.
        val syntheticAmp = if (transcribing) {
            val s = (sin(phaseFast * 2.7f + 0.6f).toFloat() + 1f) * 0.5f
            0.20f + 0.20f * s
        } else 0f
        val effectiveAmp = max(ampNorm, syntheticAmp)

        clipPath(watchFace) {
            // -------- Aurora curtains --------
            // Five bands. Ordered back-to-front so the brighter / whiter
            // ridges sit on top of the violet washes.
            //
            // freq is "wave cycles across the canvas width". Keeping
            // these in a 0.7..2.2 range avoids the bands resolving into
            // a single visual stripe.
            val bands = listOf(
                AuroraBand(
                    tipColor = Color(0xFFB39CFF),   // lilac wash (back)
                    midColor = Color(0xFF7556E8),   // deeper violet
                    baseFrac = 0.58f,
                    waveAmpFrac = 0.055f,
                    freq = 0.95f,
                    phase = phaseMed * 0.9f + 3.1f,
                    opacity = 0.45f,
                ),
                AuroraBand(
                    tipColor = Color(0xFFE6DAFF),   // pale lilac
                    midColor = Color(0xFF8A6FFF),
                    baseFrac = 0.65f,
                    waveAmpFrac = 0.05f,
                    freq = 1.3f,
                    phase = phaseSlow,
                    opacity = 0.6f,
                ),
                AuroraBand(
                    tipColor = Color(0xFFFFFFFF),   // pure white ridge
                    midColor = Color(0xFFB498FF),
                    baseFrac = 0.72f,
                    waveAmpFrac = 0.045f,
                    freq = 1.1f,
                    phase = phaseMed + 1.4f,
                    opacity = 0.85f,
                ),
                AuroraBand(
                    tipColor = Color(0xFFFFFFFF),   // bright white, faster wobble
                    midColor = Color(0xFFC9B5FF),
                    baseFrac = 0.69f,
                    waveAmpFrac = 0.05f,
                    freq = 1.9f,
                    phase = phaseFast + 2.2f,
                    opacity = 0.7f,
                ),
                AuroraBand(
                    tipColor = Color(0xFFF4ECFF),   // near-white front, slow drift
                    midColor = Color(0xFF9C7DFF),
                    baseFrac = 0.76f,
                    waveAmpFrac = 0.04f,
                    freq = 0.75f,
                    phase = phaseSlow * 1.4f + 0.5f,
                    opacity = 0.55f,
                ),
            )

            for (band in bands) {
                drawAuroraBand(
                    band = band,
                    intensity = animatedIntensity,
                    amplitude = effectiveAmp,
                    samples = 56,
                )
            }

            // -------- Horizon glow --------
            // Subtle additive light along the very bottom edge so the
            // curtains feel like they're rooted in something rather than
            // hovering. Brightens slightly with intensity.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFFB39CFF).copy(alpha = 0.10f + 0.10f * animatedIntensity),
                        Color(0xFFE8DFFF).copy(alpha = 0.18f + 0.12f * animatedIntensity),
                    ),
                    startY = h * 0.82f,
                    endY = h,
                ),
            )

            // -------- Transcribing shimmer --------
            // Travels across the lower half only; the top is reserved
            // for the status text and shouldn't pick up stray light.
            if (transcribing) {
                val sweepX = w * shimmer
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.22f),
                            Color(0xFFD7C6FF).copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                        start = Offset(sweepX - w * 0.22f, h * 0.5f),
                        end = Offset(sweepX + w * 0.22f, h),
                    ),
                    topLeft = Offset(0f, h * 0.50f),
                    size = Size(w, h * 0.50f),
                )
            }
        }
    }
}

/**
 * Single curtain of light. Each band is rendered as a polygon whose top
 * edge undulates according to its own phase + frequency, with a vertical
 * gradient underneath that fades white → violet → transparent.
 *
 * @param tipColor the colour painted right along the wavy ridge — almost
 *   always white-ish so the user reads each curtain as "a streak of
 *   light".
 * @param midColor the colour the band fades to a third of the way down,
 *   before going fully transparent at the bottom of the canvas. This is
 *   where the lilac / violet character of the aurora lives.
 * @param baseFrac y position of the ridge as a fraction of canvas
 *   height, before amplitude / wave displacement.
 * @param waveAmpFrac vertical wobble amplitude as a fraction of height.
 * @param freq number of wave cycles spanning the canvas width.
 * @param phase animated phase offset (radians).
 * @param opacity peak alpha of the band at its tip.
 */
private data class AuroraBand(
    val tipColor: Color,
    val midColor: Color,
    val baseFrac: Float,
    val waveAmpFrac: Float,
    val freq: Float,
    val phase: Float,
    val opacity: Float,
)

private fun DrawScope.drawAuroraBand(
    band: AuroraBand,
    intensity: Float,
    amplitude: Float,
    samples: Int,
) {
    val w = size.width
    val h = size.height

    // Amplitude both lifts the ridge upward and exaggerates the wobble.
    // The lift is more aggressive than the wobble because vertical
    // displacement reads more clearly on a small watch face than
    // changes in wave shape.
    val ampLiftPx = h * 0.16f * amplitude
    val waveAmpPx = (h * band.waveAmpFrac) * (1f + 0.6f * amplitude)
    val baseY = band.baseFrac * h - ampLiftPx

    // Sample the wavy ridge. Three sines summed together avoid the
    // tell-tale single-sinusoid silhouette: a primary wave, a slower
    // drift to nudge the whole ridge up/down, and a smaller overtone
    // for the choppy "aurora wisp" feel.
    val topYs = FloatArray(samples + 1)
    var ridgeMin = Float.MAX_VALUE
    for (i in 0..samples) {
        val t = i.toFloat() / samples
        val w1 = sin(t * band.freq * 2 * PI + band.phase).toFloat()
        val w2 = sin(t * band.freq * 0.6 * PI + band.phase * 1.3 + 1.0).toFloat() * 0.55f
        val w3 = sin(t * band.freq * 3.7 * PI + band.phase * 0.7 + 2.4).toFloat() * 0.28f
        val wave = (w1 + w2 + w3) / 1.83f
        val y = baseY + wave * waveAmpPx
        topYs[i] = y
        if (y < ridgeMin) ridgeMin = y
    }

    // Fill polygon: the wavy ridge across the top, then two corners down
    // to the canvas bottom so the gradient has somewhere to fade into.
    val fillPath = Path().apply {
        moveTo(0f, topYs[0])
        for (i in 1..samples) lineTo(i.toFloat() / samples * w, topYs[i])
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }

    // Amplitude also brightens the band slightly so loud bits "pop".
    val alphaBoost = 1f + 0.25f * amplitude
    val tipAlpha = (band.opacity * intensity * alphaBoost).coerceIn(0f, 1f)
    val midAlpha = (band.opacity * 0.45f * intensity * alphaBoost).coerceIn(0f, 1f)

    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(
                band.tipColor.copy(alpha = tipAlpha),
                band.midColor.copy(alpha = midAlpha),
                band.midColor.copy(alpha = midAlpha * 0.35f),
                band.midColor.copy(alpha = 0f),
            ),
            startY = ridgeMin,
            endY = h,
        ),
    )

    // Thin highlight stroke along the ridge itself. Without this the
    // ridge can look soft on dim bands; the highlight gives every
    // curtain a definite "edge of light" you can follow with the eye.
    val ridgePath = Path().apply {
        moveTo(0f, topYs[0])
        for (i in 1..samples) lineTo(i.toFloat() / samples * w, topYs[i])
    }
    drawPath(
        path = ridgePath,
        color = band.tipColor.copy(
            alpha = (band.opacity * 0.65f * intensity * alphaBoost).coerceIn(0f, 0.85f),
        ),
        style = Stroke(width = 1.5.dp.toPx()),
    )
}
