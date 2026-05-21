package de.aploi.sussurrobyeyed.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Sans = FontFamily.SansSerif

/**
 * Sussurro typography. Calm, tight, restrained.
 *
 * Material 3 Expressive ships a slightly larger, friendlier scale than the
 * 2021 Material 3 baseline; we still tighten letter spacing a notch because
 * the all-black-and-white palette already does a lot of the heavy lifting.
 */
internal val SussurroTypography: Typography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold,    fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold,   fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-0.4).sp),
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold,    fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.2).sp),

    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),

    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,  fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,   fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,    fontSize = 13.sp, lineHeight = 18.sp),

    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal,     fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal,    fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal,     fontSize = 11.sp, lineHeight = 16.sp),

    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,  fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold,      fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp),
)
