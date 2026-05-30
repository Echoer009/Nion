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
 * 构建亮色模式 ColorScheme —— 所有颜色均从 ThemePalette 或 NionColors 常量获取，
 * 不在此函数内硬编码任何 Color(0x…) 值。
 *
 * @param p 当前主题色板，包含所有颜色槽位
 */
private fun buildLightScheme(p: ThemePalette) = lightColorScheme(
    primary = p.primary,
    onPrimary = Color.White,
    primaryContainer = p.primaryContainer,
    onPrimaryContainer = p.onPrimaryContainer,
    inversePrimary = p.darkPrimary,
    secondary = p.secondary,
    onSecondary = Color.White,
    secondaryContainer = p.secondaryContainer,
    onSecondaryContainer = p.onSecondaryContainer,
    tertiary = p.tertiary,
    onTertiary = Color.White,
    tertiaryContainer = p.tertiaryContainer,
    onTertiaryContainer = p.onTertiaryContainer,
    error = NionColors.ErrorLight,
    onError = Color.White,
    errorContainer = NionColors.ErrorContainerLight,
    onErrorContainer = NionColors.OnErrorContainerLight,
    background = p.lightBackground,
    onBackground = p.lightOnBackground,
    surface = p.lightBackground,
    onSurface = p.lightOnBackground,
    surfaceVariant = p.lightSurfaceVariant,
    onSurfaceVariant = p.lightOnSurfaceVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = p.lightBackground,
    surfaceContainer = p.lightSurfaceVariant,
    surfaceContainerHigh = p.lightSurfaceHigh,
    surfaceContainerHighest = p.lightSurfaceHighest,
    inverseSurface = NionColors.InverseSurfaceLight,
    inverseOnSurface = NionColors.InverseOnSurfaceLight,
    outline = p.lightOutline,
    outlineVariant = p.lightOutlineVariant,
    scrim = Color.Black,
)

/**
 * 构建暗色模式 ColorScheme —— 所有颜色均从 ThemePalette 或 NionColors 常量获取，
 * 不在此函数内硬编码任何 Color(0x…) 值。
 *
 * @param p 当前主题色板，包含所有颜色槽位
 */
private fun buildDarkScheme(p: ThemePalette) = darkColorScheme(
    primary = p.darkPrimary,
    onPrimary = p.darkOnPrimary,
    primaryContainer = p.primary,
    onPrimaryContainer = p.primaryContainer,
    inversePrimary = p.primary,
    secondary = p.darkSecondary,
    onSecondary = p.darkOnSecondary,
    secondaryContainer = p.darkSecondaryContainer,
    onSecondaryContainer = p.secondaryContainer,
    tertiary = p.darkTertiary,
    onTertiary = p.darkOnTertiary,
    tertiaryContainer = p.darkTertiaryContainer,
    onTertiaryContainer = p.tertiaryContainer,
    error = NionColors.ErrorDark,
    onError = NionColors.OnErrorDark,
    errorContainer = NionColors.ErrorContainerDark,
    onErrorContainer = NionColors.ErrorContainerLight,
    background = p.darkBackground,
    onBackground = p.darkOnBackground,
    surface = p.darkSurface,
    onSurface = p.darkOnBackground,
    surfaceVariant = p.darkSurfaceHigh,
    onSurfaceVariant = p.darkOnSurfaceVariant,
    surfaceContainerLowest = NionColors.SurfaceContainerLowestDark,
    surfaceContainerLow = p.darkBackground,
    surfaceContainer = p.darkSurfaceHigh,
    surfaceContainerHigh = p.darkSurfaceHighest,
    surfaceContainerHighest = NionColors.SurfaceContainerHighestDark,
    inverseSurface = NionColors.InverseSurfaceDark,
    inverseOnSurface = NionColors.InverseOnSurfaceDark,
    outline = NionColors.OutlineDark,
    outlineVariant = NionColors.OutlineVariantDark,
    scrim = Color.Black,
)

/**
 * Nion 全局主题包装 —— 接收当前主题色板和暗色模式开关，
 * 构建 Material 3 ColorScheme 并注入到 Compose 树中。
 *
 * 支持预设主题（通过 [NionColorTheme.palette()]）和自定义主题（通过 [ThemePalette] 直接传入）。
 *
 * @param palette 当前主题色板，包含所有颜色槽位
 * @param darkTheme 是否启用暗色模式，默认跟随系统
 * @param dynamicColor 是否使用动态取色（Android 12+），默认关闭
 */
@Composable
fun NionTheme(
    palette: ThemePalette = NionColorTheme.CORAL.palette(),
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
        darkTheme -> buildDarkScheme(palette)
        else -> buildLightScheme(palette)
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
