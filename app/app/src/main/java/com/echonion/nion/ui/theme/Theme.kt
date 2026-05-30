package com.echonion.nion.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 优先级颜色数据 —— 通过 CompositionLocal 向下传递，让 UI 组件
 * 可以访问跟随主题变化的优先级颜色（而非硬编码 NionColors）。
 *
 * @property high 紧急优先级色
 * @property medium 中等优先级色
 * @property low 低优先级色
 */
data class PriorityColors(
    val high: Color,
    val medium: Color,
    val low: Color,
)

/**
 * 优先级颜色的 CompositionLocal。
 * 在 NionTheme 中提供，UI 组件通过 LocalPriorityColors.current 访问。
 */
val LocalPriorityColors = staticCompositionLocalOf {
    PriorityColors(
        high = Color(0xFFD32F2F),
        medium = Color(0xFFF4511E),
        low = Color(0xFF607D8B),
    )
}

/**
 * 构建 ColorScheme —— 所有颜色均从 ThemePalette 或 NionColors 常量获取，
 * 不在此函数内硬编码任何 Color(0x…) 值。
 *
 * @param p 当前主题色板，包含所有颜色槽位
 */
private fun buildScheme(p: ThemePalette) = lightColorScheme(
    primary = p.primary,
    onPrimary = Color.White,
    primaryContainer = p.primaryContainer,
    onPrimaryContainer = p.onPrimaryContainer,
    inversePrimary = p.primary,
    secondary = p.secondary,
    onSecondary = Color.White,
    secondaryContainer = p.secondaryContainer,
    onSecondaryContainer = p.onSecondaryContainer,
    tertiary = p.tertiary,
    onTertiary = Color.White,
    tertiaryContainer = p.tertiaryContainer,
    onTertiaryContainer = p.onTertiaryContainer,
    error = NionColors.Error,
    onError = Color.White,
    errorContainer = NionColors.ErrorContainer,
    onErrorContainer = NionColors.OnErrorContainer,
    background = p.background,
    onBackground = p.onBackground,
    surface = p.background,
    onSurface = p.onBackground,
    surfaceVariant = p.surfaceVariant,
    onSurfaceVariant = p.onSurfaceVariant,
    surfaceContainerLowest = p.cardBackground,
    surfaceContainerLow = p.background,
    surfaceContainer = p.surfaceVariant,
    surfaceContainerHigh = p.surfaceHigh,
    surfaceContainerHighest = p.surfaceHighest,
    inverseSurface = NionColors.InverseSurface,
    inverseOnSurface = NionColors.InverseOnSurface,
    outline = p.outline,
    outlineVariant = p.outlineVariant,
    scrim = Color.Black,
)

/**
 * Nion 全局主题包装 —— 接收当前主题色板，
 * 构建 Material 3 ColorScheme 并注入到 Compose 树中。
 *
 * 支持预设主题（通过 [NionColorTheme.palette()]）和自定义主题（通过 [ThemePalette] 直接传入）。
 *
 * @param palette 当前主题色板，包含所有颜色槽位
 */
@Composable
fun NionTheme(
    palette: ThemePalette = NionColorTheme.CORAL.palette(),
    content: @Composable () -> Unit,
) {
    val colorScheme = buildScheme(palette)

    // 提供优先级颜色，让 UI 组件通过 LocalPriorityColors.current 访问
    val priorityColors = PriorityColors(
        high = palette.priorityHigh,
        medium = palette.priorityMedium,
        low = palette.priorityLow,
    )

    // 状态栏/导航栏图标使用深色（适配浅色背景）
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            window?.let {
                WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = true
                WindowCompat.getInsetsController(it, view).isAppearanceLightNavigationBars = true
            }
        }
    }

    // 优先级颜色通过 CompositionLocal 传递，不污染 MaterialTheme.colorScheme
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPriorityColors provides priorityColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NionTypography,
            shapes = NionShapes,
            content = content,
        )
    }
}
