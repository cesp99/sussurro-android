package de.aploi.sussurrobyeyed.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import de.aploi.sussurrobyeyed.audio.SilenceTrimmer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Compact waveform of a captured buffer with silence regions shaded out.
 *
 * Per-pixel column we render two things:
 *  1. A background tint over the columns that fall in the leading or
 *     trailing region the [SilenceTrimmer] decided to cut. Internal silence
 *     stays the same colour as speech — we don't drop those.
 *  2. A min/max envelope of the float samples in that column, vertically
 *     centred. The envelope colour is `keptColor` for speech and
 *     `trimmedColor` for cut regions.
 *
 * The RMS threshold is drawn as a faint horizontal line on either side of
 * the centre so you can eyeball how aggressive the trim was.
 *
 * Intentionally not animated — this is a snapshot.
 */
@Composable
fun Waveform(
    audio: FloatArray,
    trim: SilenceTrimmer.Result?,
    modifier: Modifier = Modifier,
    keptColor: Color = MaterialTheme.colorScheme.primary,
    trimmedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    thresholdColor: Color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
    backgroundTrimColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    // Resolve trim region against the original buffer.
    val trimStart = trim?.leadingSamplesCut ?: 0
    val trimEnd = audio.size - (trim?.trailingSamplesCut ?: 0)

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (audio.isEmpty() || size.width <= 0 || size.height <= 0) return@Canvas

            val w = size.width
            val h = size.height
            val cy = h / 2f
            val cols = w.toInt().coerceAtLeast(1)
            val samplesPerCol = max(1, audio.size / cols)

            // 1. Shade leading/trailing silence as a background block. We
            // convert sample indices to column indices via the same
            // samplesPerCol bucketing, so the tint lines up with the envelope.
            val leadCols = (trimStart / samplesPerCol).coerceAtMost(cols)
            val trailColsStart = (trimEnd / samplesPerCol).coerceAtMost(cols)
            if (leadCols > 0) {
                drawRect(
                    color = backgroundTrimColor,
                    topLeft = Offset(0f, 0f),
                    size = Size(leadCols.toFloat(), h),
                )
            }
            if (trailColsStart < cols) {
                drawRect(
                    color = backgroundTrimColor,
                    topLeft = Offset(trailColsStart.toFloat(), 0f),
                    size = Size((cols - trailColsStart).toFloat(), h),
                )
            }

            // 2. Min/max envelope per column.
            var idx = 0
            for (col in 0 until cols) {
                if (idx >= audio.size) break
                val end = min(audio.size, idx + samplesPerCol)
                var mn = 0f
                var mx = 0f
                for (i in idx until end) {
                    val s = audio[i]
                    if (s < mn) mn = s
                    if (s > mx) mx = s
                }
                idx = end

                // Floor a 1 px line so silent columns still render something.
                val top = cy - max(abs(mx), 0f) * cy
                val bottom = cy + max(abs(mn), 0f) * cy
                val safeTop = if (bottom - top < 1f) cy - 0.5f else top
                val safeBottom = if (bottom - top < 1f) cy + 0.5f else bottom

                val color = if (col < leadCols || col >= trailColsStart) {
                    trimmedColor
                } else {
                    keptColor
                }

                drawLine(
                    color = color,
                    start = Offset(col.toFloat() + 0.5f, safeTop),
                    end = Offset(col.toFloat() + 0.5f, safeBottom),
                    strokeWidth = 1.5f,
                    cap = StrokeCap.Round,
                )
            }

            // 3. RMS threshold lines (mirror above + below centre).
            if (trim != null && trim.rmsThreshold > 0f) {
                val ty = trim.rmsThreshold.coerceAtMost(1f) * cy
                drawLine(
                    color = thresholdColor,
                    start = Offset(0f, cy - ty),
                    end = Offset(w, cy - ty),
                    strokeWidth = 1f,
                )
                drawLine(
                    color = thresholdColor,
                    start = Offset(0f, cy + ty),
                    end = Offset(w, cy + ty),
                    strokeWidth = 1f,
                )
            }
        }
    }
}


