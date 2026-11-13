package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val RedlineDarkColorScheme = darkColorScheme(
    primary = CrimsonPrimary,
    onPrimary = Color.White,
    primaryContainer = CrimsonContainerDark,
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = CoralSecondary,
    onSecondary = Color(0xFF2B0811),
    secondaryContainer = Color(0xFF2C161D),
    onSecondaryContainer = Color(0xFFFFD9E0),
    tertiary = SpectatorCyan,
    onTertiary = Color(0xFF002026),
    tertiaryContainer = Color(0xFF0B2E36),
    onTertiaryContainer = Color(0xFFA5F3FC),
    background = ObsidianBg,
    onBackground = InkWhite,
    surface = ObsidianSurface,
    onSurface = InkWhite,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = InkMuted,
    outline = ObsidianOutline,
    error = CrimsonBright,
    onError = Color.White
)

private val RedlineLightColorScheme = lightColorScheme(
    primary = CrimsonPrimary,
    onPrimary = Color.White,
    primaryContainer = CrimsonContainerLight,
    onPrimaryContainer = CrimsonDeep,
    secondary = CrimsonDeep,
    onSecondary = Color.White,
    secondaryContainer = AlabasterSurfaceVariant,
    onSecondaryContainer = InkObsidian,
    tertiary = Color(0xFF0891B2),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFFAFE),
    onTertiaryContainer = Color(0xFF083344),
    background = AlabasterBg,
    onBackground = InkObsidian,
    surface = AlabasterSurface,
    onSurface = InkObsidian,
    surfaceVariant = AlabasterSurfaceVariant,
    onSurfaceVariant = InkCharcoalMuted,
    outline = AlabasterOutline,
    error = CrimsonDeep,
    onError = Color.White
)

val RedlineShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun RedlineTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) RedlineDarkColorScheme else RedlineLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = RedlineTypography,
        shapes = RedlineShapes,
        content = content
    )
}
