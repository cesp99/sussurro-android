package de.aploi.sussurrobyeyed.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * Sussurro's Material 3 Expressive theme.
 *
 * No Material You / Monet — the palette is locked to black & white. The user
 * can opt into system / light / dark from the settings screen; the resolved
 * mode is passed in via [darkTheme].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SussurroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme

    // Tint the system bars to match the scheme. Compose-side window insets are
    // already edge-to-edge thanks to the Activity setup.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = scheme.background.toArgb()
                window.navigationBarColor = scheme.background.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        typography = SussurroTypography,
        motionScheme = if (Build.VERSION.SDK_INT >= 31) MotionScheme.expressive() else MotionScheme.standard(),
        content = content,
    )
}

private val LightScheme = lightColorScheme(
    primary = SussurroPalette.Black,
    onPrimary = SussurroPalette.White,
    primaryContainer = SussurroPalette.LightSurfaceContainerHigh,
    onPrimaryContainer = SussurroPalette.Black,

    secondary = SussurroPalette.Black,
    onSecondary = SussurroPalette.White,
    secondaryContainer = SussurroPalette.LightSurfaceContainer,
    onSecondaryContainer = SussurroPalette.Black,

    tertiary = SussurroPalette.Black,
    onTertiary = SussurroPalette.White,
    tertiaryContainer = SussurroPalette.LightSurfaceContainer,
    onTertiaryContainer = SussurroPalette.Black,

    background = SussurroPalette.White,
    onBackground = SussurroPalette.Black,

    surface = SussurroPalette.LightSurface,
    onSurface = SussurroPalette.Black,
    surfaceVariant = SussurroPalette.LightSurfaceContainer,
    onSurfaceVariant = SussurroPalette.LightMuted,

    surfaceContainerLowest = SussurroPalette.White,
    surfaceContainerLow = SussurroPalette.LightSurfaceLow,
    surfaceContainer = SussurroPalette.LightSurfaceContainer,
    surfaceContainerHigh = SussurroPalette.LightSurfaceContainerHigh,
    surfaceContainerHighest = SussurroPalette.LightSurfaceContainerHigh,

    outline = SussurroPalette.LightOutline,
    outlineVariant = SussurroPalette.LightOutlineVariant,

    inverseSurface = SussurroPalette.Black,
    inverseOnSurface = SussurroPalette.White,
    inversePrimary = SussurroPalette.White,
)

private val DarkScheme = darkColorScheme(
    primary = SussurroPalette.White,
    onPrimary = SussurroPalette.Black,
    primaryContainer = SussurroPalette.DarkSurfaceContainerHigh,
    onPrimaryContainer = SussurroPalette.White,

    secondary = SussurroPalette.White,
    onSecondary = SussurroPalette.Black,
    secondaryContainer = SussurroPalette.DarkSurfaceContainer,
    onSecondaryContainer = SussurroPalette.White,

    tertiary = SussurroPalette.White,
    onTertiary = SussurroPalette.Black,
    tertiaryContainer = SussurroPalette.DarkSurfaceContainer,
    onTertiaryContainer = SussurroPalette.White,

    background = SussurroPalette.DarkSurface,
    onBackground = SussurroPalette.White,

    surface = SussurroPalette.DarkSurface,
    onSurface = SussurroPalette.White,
    surfaceVariant = SussurroPalette.DarkSurfaceContainer,
    onSurfaceVariant = SussurroPalette.DarkMuted,

    surfaceContainerLowest = SussurroPalette.Black,
    surfaceContainerLow = SussurroPalette.DarkSurfaceLow,
    surfaceContainer = SussurroPalette.DarkSurfaceContainer,
    surfaceContainerHigh = SussurroPalette.DarkSurfaceContainerHigh,
    surfaceContainerHighest = SussurroPalette.DarkSurfaceContainerHighest,

    outline = SussurroPalette.DarkOutline,
    outlineVariant = SussurroPalette.DarkOutlineVariant,

    inverseSurface = SussurroPalette.White,
    inverseOnSurface = SussurroPalette.Black,
    inversePrimary = SussurroPalette.Black,
)

