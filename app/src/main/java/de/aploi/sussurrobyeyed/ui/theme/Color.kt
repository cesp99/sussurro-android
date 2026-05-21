package de.aploi.sussurrobyeyed.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Sussurro is intentionally monochrome — no Material You, no Monet, no hue.
 * Every value below is either pure black or pure white, with a handful of
 * carefully chosen neutral greys for surfaces and dividers.
 */
internal object SussurroPalette {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)

    // Light scheme greys
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceLow = Color(0xFFFAFAFA)
    val LightSurfaceContainer = Color(0xFFF2F2F2)
    val LightSurfaceContainerHigh = Color(0xFFEAEAEA)
    val LightOutline = Color(0xFFD0D0D0)
    val LightOutlineVariant = Color(0xFFE2E2E2)
    val LightMuted = Color(0xFF6B6B6B)

    // Dark scheme greys — modelled on the capsule background (#1A1A1A)
    val DarkSurface = Color(0xFF000000)
    val DarkSurfaceLow = Color(0xFF0A0A0A)
    val DarkSurfaceContainer = Color(0xFF141414)
    val DarkSurfaceContainerHigh = Color(0xFF1C1C1E)
    val DarkSurfaceContainerHighest = Color(0xFF242426)
    val DarkOutline = Color(0xFF2E2E30)
    val DarkOutlineVariant = Color(0xFF1F1F21)
    val DarkMuted = Color(0xFF8B8B8E)
}
