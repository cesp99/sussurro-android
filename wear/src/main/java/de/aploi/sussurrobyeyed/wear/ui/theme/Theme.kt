package de.aploi.sussurrobyeyed.wear.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for Sussurro's watch palette.
 *
 * The watch UI is intentionally minimal: a deep black background with a
 * cool bluish-white accent that drives the waveform's glow. Keeping these
 * values here means the screen, the waveform, and any future ambient /
 * AOD variant can share the same definitions without redeclaring raw
 * `Color(0xFF…)` literals at the call site.
 *
 * Material's [androidx.wear.compose.material.MaterialTheme] would
 * normally provide this, but Sussurro draws its UI by hand on a
 * [androidx.compose.foundation.Canvas] for the most part, so a tiny
 * value-class object is a better fit than wiring up Material colours.
 */
object WatchColors {

    /** Whole-screen background. Pure black so OLED watches save power. */
    val Background: Color = Color.Black

    /** Primary on-background colour (status text, bar fill). */
    val OnBackground: Color = Color.White

    /**
     * Secondary on-background colour (sub-label hint under the status
     * text). Same hue as [OnBackground] but heavily faded so the eye
     * settles on the primary line first.
     */
    val OnBackgroundMuted: Color = Color.White.copy(alpha = 0.5f)

    /**
     * Bluish halo that the waveform paints behind its bars and along
     * the bottom edge. Matches the screenshot reference attached to
     * the initial design brief.
     */
    val Glow: Color = Color(0xFF8AB4FF)
}
