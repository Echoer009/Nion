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

/**
 * 构建亮色模式 ColorScheme —— 所有颜色均从主题枚举或 NionColors 常量获取，
 * 不在此函数内硬编码任何 Color(0x…) 值。
 */
private fun buildLightScheme(theme: NionColorTheme) = lightColorScheme(
    primary = theme.primary,
    onPrimary = Color.White,
    primaryContainer = theme.primaryContainer,
    onPrimaryContainer = theme.onPrimaryContainer,
    inversePrimary = theme.darkPrimary,
    secondary = theme.secondary,
    onSecondary = Color.White,
    secondaryContainer = theme.secondaryContainer,
    onSecondaryContainer = theme.onSecondaryContainer,
    tertiary = theme.tertiary,
    onTertiary = Color.White,
    tertiaryContainer = theme.tertiaryContainer,
    onTertiaryContainer = theme.onTertiaryContainer,
    error = NionColors.ErrorLight,
    onError = Color.White,
    errorContainer = NionColors.ErrorContainerLight,
    onErrorContainer = NionColors.OnErrorContainerLight,
    background = theme.lightBackground,
    onBackground = theme.lightOnBackground,
    surface = theme.lightBackground,
    onSurface = theme.lightOnBackground,
    surfaceVariant = theme.lightSurfaceVariant,
    onSurfaceVariant = theme.lightOnSurfaceVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = theme.lightBackground,
    surfaceContainer = theme.lightSurfaceVariant,
    surfaceContainerHigh = theme.lightSurfaceHigh,
    surfaceContainerHighest = theme.lightSurfaceHighest,
    inverseSurface = NionColors.InverseSurfaceLight,
    inverseOnSurface = NionColors.InverseOnSurfaceLight,
    outline = theme.lightOutline,
    outlineVariant = theme.lightOutlineVariant,
    scrim = Color.Black,
)

/**
 * 构建暗色模式 ColorScheme —— 所有颜色均从主题枚举或 NionColors 常量获取，
 * 不在此函数内硬编码任何 Color(0x…) 值。
 */
private fun buildDarkScheme(theme: NionColorTheme) = darkColorScheme(
    primary = theme.darkPrimary,
    onPrimary = theme.darkOnPrimary,
    primaryContainer = theme.primary,
    onPrimaryContainer = theme.primaryContainer,
    inversePrimary = theme.primary,
    secondary = theme.darkSecondary,
    onSecondary = theme.darkOnSecondary,
    secondaryContainer = theme.darkSecondaryContainer,
    onSecondaryContainer = theme.secondaryContainer,
    tertiary = theme.darkTertiary,
    onTertiary = theme.darkOnTertiary,
    tertiaryContainer = theme.darkTertiaryContainer,
    onTertiaryContainer = theme.tertiaryContainer,
    error = NionColors.ErrorDark,
    onError = NionColors.OnErrorDark,
    errorContainer = NionColors.ErrorContainerDark,
    onErrorContainer = NionColors.ErrorContainerLight,
    background = theme.darkBackground,
    onBackground = theme.darkOnBackground,
    surface = theme.darkSurface,
    onSurface = theme.darkOnBackground,
    surfaceVariant = theme.darkSurfaceHigh,
    onSurfaceVariant = theme.darkOnSurfaceVariant,
    surfaceContainerLowest = NionColors.SurfaceContainerLowestDark,
    surfaceContainerLow = theme.darkBackground,
    surfaceContainer = theme.darkSurfaceHigh,
    surfaceContainerHigh = theme.darkSurfaceHighest,
    surfaceContainerHighest = NionColors.SurfaceContainerHighestDark,
    inverseSurface = NionColors.InverseSurfaceDark,
    inverseOnSurface = NionColors.InverseOnSurfaceDark,
    outline = NionColors.OutlineDark,
    outlineVariant = NionColors.OutlineVariantDark,
    scrim = Color.Black,
)

/**
 * Nion 全局主题包装 —— 接收当前主题枚举和暗色模式开关，
 * 构建 Material 3 ColorScheme 并注入到 Compose 树中。
 *
 * @param colorTheme 当前选中的主题色板，默认 CLAUDE
 * @param darkTheme 是否启用暗色模式，默认跟随系统
 * @param dynamicColor 是否使用动态取色（Android 12+），默认关闭
 */
@Composable
fun NionTheme(
    colorTheme: NionColorTheme = NionColorTheme.CORAL,
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
