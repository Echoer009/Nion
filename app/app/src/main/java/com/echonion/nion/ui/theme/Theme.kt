package com.echonion.nion.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun buildLightScheme(theme: NionColorTheme) = lightColorScheme(
    primary = theme.primary,
    onPrimary = Color.White,
    primaryContainer = theme.primaryContainer,
    onPrimaryContainer = theme.onPrimaryContainer,
    inversePrimary = theme.darkPrimary,
    secondary = Color(0xFF7C5800),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDEA6),
    onSecondaryContainer = Color(0xFF271900),
    tertiary = Color(0xFF6D5E0F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF8E287),
    onTertiaryContainer = Color(0xFF221B00),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = NionColors.Warm50,
    onBackground = Color(0xFF1C1B18),
    surface = NionColors.Warm50,
    onSurface = Color(0xFF1C1B18),
    surfaceVariant = NionColors.Warm100,
    onSurfaceVariant = Color(0xFF49453F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = NionColors.Warm50,
    surfaceContainer = NionColors.Warm100,
    surfaceContainerHigh = NionColors.Warm200,
    surfaceContainerHighest = NionColors.Warm300,
    inverseSurface = Color(0xFF32302D),
    inverseOnSurface = Color(0xFFF5F0EB),
    outline = Color(0xFF7B756E),
    outlineVariant = Color(0xFFCBC5BD),
    scrim = Color.Black,
)

private fun buildDarkScheme(theme: NionColorTheme) = darkColorScheme(
    primary = theme.darkPrimary,
    onPrimary = theme.darkOnPrimary,
    primaryContainer = theme.primary,
    onPrimaryContainer = theme.primaryContainer,
    inversePrimary = theme.primary,
    secondary = Color(0xFFF2C14A),
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF5D4300),
    onSecondaryContainer = Color(0xFFFFDEA6),
    tertiary = Color(0xFFDBC66E),
    onTertiary = Color(0xFF3A3000),
    tertiaryContainer = Color(0xFF534600),
    onTertiaryContainer = Color(0xFFF8E287),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = NionColors.DarkBackground,
    onBackground = Color(0xFFE6E2DA),
    surface = NionColors.DarkSurface,
    onSurface = Color(0xFFE6E2DA),
    surfaceVariant = NionColors.DarkSurfaceHigh,
    onSurfaceVariant = Color(0xFFCBC5BD),
    surfaceContainerLowest = Color(0xFF0F0E0C),
    surfaceContainerLow = NionColors.DarkBackground,
    surfaceContainer = NionColors.DarkSurfaceHigh,
    surfaceContainerHigh = NionColors.DarkSurfaceHighest,
    surfaceContainerHighest = Color(0xFF3D3834),
    inverseSurface = Color(0xFFE6E2DA),
    inverseOnSurface = Color(0xFF32302D),
    outline = Color(0xFF948F88),
    outlineVariant = Color(0xFF49453F),
    scrim = Color.Black,
)

@Composable
fun NionTheme(
    colorTheme: NionColorTheme = NionColorTheme.BURNT_ORANGE,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> buildDarkScheme(colorTheme)
        else -> buildLightScheme(colorTheme)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            window?.let {
                WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(it, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NionTypography,
        shapes = NionShapes,
        content = content,
    )
}
