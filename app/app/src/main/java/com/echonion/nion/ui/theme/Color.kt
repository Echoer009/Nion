package com.echonion.nion.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 全局颜色常量 —— 所有业务颜色统一定义在此，UI 代码通过常量名引用，
 * 禁止在 Theme 以外的文件中硬编码 Color(0x…)。
 */
object NionColors {

    // ── 优先级颜色（任务优先级标签） ──
    val PriorityHigh = Color(0xFFD32F2F)
    val PriorityMedium = Color(0xFFF4511E)
    val PriorityLow = Color(0xFF607D8B)

    // ── 偏好类别颜色（Companion 偏好标签） ──
    val PrefStyle = Color(0xFF6750A4)
    val PrefBehavior = Color(0xFF0061A4)
    val PrefFormat = Color(0xFF006E1C)
    val PrefOther = Color(0xFF757575)

    // ── 记忆类别颜色（Companion 记忆标签） ──
    val MemoryIdentity = Color(0xFF6750A4)
    val MemoryStudy = Color(0xFF1565C0)
    val MemoryWork = Color(0xFF2E7D32)
    val MemoryHobby = Color(0xFFE65100)
    val MemoryHabit = Color(0xFF6A1B9A)
    val MemoryHealth = Color(0xFFC62828)
    val MemoryEmotion = Color(0xFFAD1457)
    val MemoryGoal = Color(0xFF00838F)
    val MemorySchedule = Color(0xFF4527A0)
    val MemorySocial = Color(0xFF1B5E20)
    val MemoryLocation = Color(0xFF37474F)
    val MemoryPet = Color(0xFF4E342E)
    val MemoryContext = Color(0xFFEF6C00)
    val MemoryOther = Color(0xFF757575)

    // ── 暖色中性色（亮色模式背景层） ──
    val Warm50 = Color(0xFFF5F0E8)
    val Warm100 = Color(0xFFEBE3D5)
    val Warm200 = Color(0xFFE0D5C3)
    val Warm300 = Color(0xFFD3C6B0)
    val Warm400 = Color(0xFFC7B9A5)

    // ── 暗色模式表面层 ──
    val DarkSurface = Color(0xFF1C1917)
    val DarkSurfaceHigh = Color(0xFF272420)
    val DarkSurfaceHighest = Color(0xFF322E2A)
    val DarkBackground = Color(0xFF141210)

    // ── Material 3 错误色板（全局通用，不随主题变化） ──
    val ErrorLight = Color(0xFFBA1A1A)
    val ErrorContainerLight = Color(0xFFFFDAD6)
    val OnErrorContainerLight = Color(0xFF410002)
    val ErrorDark = Color(0xFFFFB4AB)
    val OnErrorDark = Color(0xFF690005)
    val ErrorContainerDark = Color(0xFF93000A)

    // ── Material 3 固定中性色（inverse/outline/scrim，全局通用） ──
    val InverseSurfaceLight = Color(0xFF32302D)
    val InverseOnSurfaceLight = Color(0xFFF5F0EB)
    val InverseSurfaceDark = Color(0xFFE6E2DA)
    val InverseOnSurfaceDark = Color(0xFF32302D)
    val OutlineDark = Color(0xFF948F88)
    val OutlineVariantDark = Color(0xFF49453F)
    val SurfaceContainerLowestDark = Color(0xFF0F0E0C)
    val SurfaceContainerHighestDark = Color(0xFF3D3834)
}

/**
 * 全局透明度常量 —— 所有 .copy(alpha = ...) 调用统一引用此对象中的常量，
 * 禁止在 UI 代码中直接使用浮点数字面量。
 *
 * 分类：
 * - TEXT_* — 文字/图标强调等级，数值越低越淡
 * - BG_*   — 背景/填充/装饰透明度
 * - OVERLAY_* — 遮罩层透明度
 * - SHADOW_*  — 卡片阴影透明度
 */
object NionAlpha {

    // ── 文字/图标强调等级 ──
    /** 高强调文字（卡片正文、引用文字、提醒正文） */
    const val TEXT_HIGH = 0.85f
    /** 中等强调文字（完成态任务文字、选中态标签文字、删除按钮文字） */
    const val TEXT_MEDIUM = 0.7f
    /** 次要文字/图标（副标题、任务计数、会话类型文字、标签名） */
    const val TEXT_SECONDARY = 0.6f
    /** 副标题/标签文字（详情页字段标签、时间戳、工具标签、建议文字） */
    const val TEXT_SUBTITLE = 0.5f
    /** 提示文字（分割线上方标签、按钮文字、贴纸名、输入提示） */
    const val TEXT_HINT = 0.4f
    /** 极淡文字（空状态标签、区块标题、Tab 图标） */
    const val TEXT_SUBTLE = 0.35f

    // ── 背景/填充等级 ──
    /** 淡背景（滑动删除背景、次要刻度线、提醒装饰线） */
    const val BG_SUBTLE = 0.3f
    /** 选中 Tab 背景（伴伴面板活跃标签底色） */
    const val BG_TAB_ACTIVE = 0.25f
    /** 极淡背景/图标（删除图标着色、最近贴纸背景） */
    const val BG_VERY_SUBTLE = 0.2f
    /** 装饰线/输入框边框（提醒卡片装饰线、聊天输入框边框） */
    const val BG_DECORATION = 0.15f
    /** 高亮底色（今日日期标记、代码块背景） */
    const val BG_HIGHLIGHT = 0.12f

    // ── 遮罩层等级 ──
    /** Modal 遮罩（详情页/日程页弹窗背景变暗） */
    const val OVERLAY_SCRIM = 0.32f
    /** 中等遮罩（附件删除按钮背景） */
    const val OVERLAY_MEDIUM = 0.5f
    /** 深色遮罩（图片全屏预览背景） */
    const val OVERLAY_DARK = 0.9f

    // ── 阴影等级 ──
    /** 卡片阴影环境光（提醒/问候卡片阴影） */
    const val SHADOW_AMBIENT = 0.3f
    /** 卡片阴影聚光（提醒/问候卡片阴影） */
    const val SHADOW_SPOT = 0.2f
}

/**
 * 主题色板枚举 —— 每个主题携带完整色板（primary/secondary/tertiary + 亮暗变体），
 * 构建 ColorScheme 时从此枚举取色，不再在 Theme.kt 中硬编码固定值。
 *
 * 每个主题包含：
 * - primary / primaryContainer / onPrimaryContainer — 主色三级
 * - darkPrimary / darkOnPrimary — 暗色模式主色
 * - secondary / secondaryContainer / onSecondaryContainer — 辅色三级
 * - darkSecondary / darkOnSecondary / darkSecondaryContainer — 暗色辅色
 * - tertiary / tertiaryContainer / onTertiaryContainer — 第三色三级
 * - darkTertiary / darkOnTertiary / darkTertiaryContainer — 暗色第三色
 */
enum class NionColorTheme(
    val label: String,
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val darkSecondary: Color,
    val darkOnSecondary: Color,
    val darkSecondaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val darkTertiary: Color,
    val darkOnTertiary: Color,
    val darkTertiaryContainer: Color,
    // ── 亮色模式表面/中性色 ──
    val lightBackground: Color,
    val lightOnBackground: Color,
    val lightSurfaceVariant: Color,
    val lightOnSurfaceVariant: Color,
    val lightSurfaceHigh: Color,
    val lightSurfaceHighest: Color,
    val lightOutline: Color,
    val lightOutlineVariant: Color,
    // ── 暗色模式表面/中性色 ──
    val darkBackground: Color,
    val darkSurface: Color,
    val darkSurfaceHigh: Color,
    val darkSurfaceHighest: Color,
    val darkOnBackground: Color,
    val darkOnSurfaceVariant: Color,
) {
    CORAL(
        label = "珊瑚",
        primary = Color(0xFFD4845A),
        primaryContainer = Color(0xFFF5D5C0),
        onPrimaryContainer = Color(0xFF8B5E3C),
        darkPrimary = Color(0xFFF0B090),
        darkOnPrimary = Color(0xFF5A3520),
        secondary = Color(0xFF9B7B6B),
        secondaryContainer = Color(0xFFF5E6DE),
        onSecondaryContainer = Color(0xFF3B2517),
        darkSecondary = Color(0xFFD4B8A5),
        darkOnSecondary = Color(0xFF3B2517),
        darkSecondaryContainer = Color(0xFF5D4A3B),
        tertiary = Color(0xFF6D5E0F),
        tertiaryContainer = Color(0xFFF8E287),
        onTertiaryContainer = Color(0xFF221B00),
        darkTertiary = Color(0xFFDBC66E),
        darkOnTertiary = Color(0xFF3A3000),
        darkTertiaryContainer = Color(0xFF534600),
        lightBackground = NionColors.Warm50,
        lightOnBackground = Color(0xFF1C1B18),
        lightSurfaceVariant = NionColors.Warm100,
        lightOnSurfaceVariant = Color(0xFF49453F),
        lightSurfaceHigh = NionColors.Warm200,
        lightSurfaceHighest = NionColors.Warm300,
        lightOutline = Color(0xFF7B756E),
        lightOutlineVariant = Color(0xFFCBC5BD),
        darkBackground = NionColors.DarkBackground,
        darkSurface = NionColors.DarkSurface,
        darkSurfaceHigh = NionColors.DarkSurfaceHigh,
        darkSurfaceHighest = NionColors.DarkSurfaceHighest,
        darkOnBackground = Color(0xFFE6E2DA),
        darkOnSurfaceVariant = Color(0xFFCBC5BD),
    ),
    AMBER(
        label = "琥珀",
        primary = Color(0xFFCC7A3E),
        primaryContainer = Color(0xFFF5DEB3),
        onPrimaryContainer = Color(0xFF8B5A2B),
        darkPrimary = Color(0xFFE8B080),
        darkOnPrimary = Color(0xFF5A3520),
        secondary = Color(0xFF9B8B6B),
        secondaryContainer = Color(0xFFF5E6CE),
        onSecondaryContainer = Color(0xFF3B3017),
        darkSecondary = Color(0xFFD4C8A5),
        darkOnSecondary = Color(0xFF3B3017),
        darkSecondaryContainer = Color(0xFF5D533B),
        tertiary = Color(0xFF6D5E0F),
        tertiaryContainer = Color(0xFFF8E287),
        onTertiaryContainer = Color(0xFF221B00),
        darkTertiary = Color(0xFFDBC66E),
        darkOnTertiary = Color(0xFF3A3000),
        darkTertiaryContainer = Color(0xFF534600),
        lightBackground = NionColors.Warm50,
        lightOnBackground = Color(0xFF1C1B18),
        lightSurfaceVariant = NionColors.Warm100,
        lightOnSurfaceVariant = Color(0xFF49453F),
        lightSurfaceHigh = NionColors.Warm200,
        lightSurfaceHighest = NionColors.Warm300,
        lightOutline = Color(0xFF7B756E),
        lightOutlineVariant = Color(0xFFCBC5BD),
        darkBackground = NionColors.DarkBackground,
        darkSurface = NionColors.DarkSurface,
        darkSurfaceHigh = NionColors.DarkSurfaceHigh,
        darkSurfaceHighest = NionColors.DarkSurfaceHighest,
        darkOnBackground = Color(0xFFE6E2DA),
        darkOnSurfaceVariant = Color(0xFFCBC5BD),
    );

    /**
     * 将枚举预设转换为 [ThemePalette] 数据类实例。
     *
     * 用于：
     * - [com.echonion.nion.ui.theme.NionTheme] 构建 Material 3 ColorScheme
     * - SettingsTool 读取预设色板作为自定义主题的基础
     * - SettingsScreen 展示预设色板信息
     */
    fun palette(): ThemePalette = ThemePalette(
        primary = primary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        darkPrimary = darkPrimary,
        darkOnPrimary = darkOnPrimary,
        secondary = secondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        darkSecondary = darkSecondary,
        darkOnSecondary = darkOnSecondary,
        darkSecondaryContainer = darkSecondaryContainer,
        tertiary = tertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        darkTertiary = darkTertiary,
        darkOnTertiary = darkOnTertiary,
        darkTertiaryContainer = darkTertiaryContainer,
        lightBackground = lightBackground,
        lightOnBackground = lightOnBackground,
        lightSurfaceVariant = lightSurfaceVariant,
        lightOnSurfaceVariant = lightOnSurfaceVariant,
        lightSurfaceHigh = lightSurfaceHigh,
        lightSurfaceHighest = lightSurfaceHighest,
        lightOutline = lightOutline,
        lightOutlineVariant = lightOutlineVariant,
        darkBackground = darkBackground,
        darkSurface = darkSurface,
        darkSurfaceHigh = darkSurfaceHigh,
        darkSurfaceHighest = darkSurfaceHighest,
        darkOnBackground = darkOnBackground,
        darkOnSurfaceVariant = darkOnSurfaceVariant,
    )
}
