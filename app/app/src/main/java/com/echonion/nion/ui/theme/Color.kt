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

    // ── 暖色中性色（背景层） ──
    val Warm50 = Color(0xFFF5F0E8)
    val Warm100 = Color(0xFFEBE3D5)
    val Warm200 = Color(0xFFE0D5C3)
    val Warm300 = Color(0xFFD3C6B0)
    val Warm400 = Color(0xFFC7B9A5)

    // ── Material 3 错误色板（全局通用，不随主题变化） ──
    val Error = Color(0xFFBA1A1A)
    val ErrorContainer = Color(0xFFFFDAD6)
    val OnErrorContainer = Color(0xFF410002)

    // ── Material 3 固定中性色（inverse/scrim，全局通用） ──
    val InverseSurface = Color(0xFF32302D)
    val InverseOnSurface = Color(0xFFF5F0EB)
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
 * 主题色板枚举 —— 每个预设主题携带完整色板（21 个槽位），
 * 构建 ColorScheme 时从此枚举取色，不再在 Theme.kt 中硬编码固定值。
 *
 * 每个主题包含：
 * - primary / primaryContainer / onPrimaryContainer — 主色三级
 * - secondary / secondaryContainer / onSecondaryContainer — 辅色三级
 * - tertiary / tertiaryContainer / onTertiaryContainer — 第三色三级
 * - background / onBackground / surfaceVariant / onSurfaceVariant — 背景/表面四级
 * - surfaceHigh / surfaceHighest / cardBackground — 表面层级
 * - outline / outlineVariant — 边框
 * - priorityHigh / priorityMedium / priorityLow — 优先级
 */
enum class NionColorTheme(
    val label: String,
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    // ── 表面/背景/中性色 ──
    val background: Color,
    val onBackground: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val cardBackground: Color,
    val outline: Color,
    val outlineVariant: Color,
    // ── 优先级颜色 ──
    val priorityHigh: Color,
    val priorityMedium: Color,
    val priorityLow: Color,
) {
    CORAL(
        label = "珊瑚",
        // 主色：鲜亮暖橙色系
        primary = Color(0xFFE07A4F),
        primaryContainer = Color(0xFFFFDBC7),
        onPrimaryContainer = Color(0xFF8B4A25),
        // 辅色：柔和暖橙，同色系但更淡雅
        secondary = Color(0xFFC48A65),
        secondaryContainer = Color(0xFFFFDDC7),
        onSecondaryContainer = Color(0xFF38200E),
        // 第三色：深焦橙/赤陶，同色系但更深沉
        tertiary = Color(0xFFA06B3E),
        tertiaryContainer = Color(0xFFFFD9B0),
        onTertiaryContainer = Color(0xFF301400),
        background = NionColors.Warm50,
        onBackground = Color(0xFF1C1B18),
        surfaceVariant = NionColors.Warm100,
        onSurfaceVariant = Color(0xFF49453F),
        surfaceHigh = NionColors.Warm200,
        surfaceHighest = NionColors.Warm300,
        cardBackground = Color.White,
        outline = Color(0xFF7B756E),
        outlineVariant = Color(0xFFCBC5BD),
        priorityHigh = Color(0xFFD32F2F),
        priorityMedium = Color(0xFFF4511E),
        priorityLow = Color(0xFF607D8B),
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
        secondary = secondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceHigh = surfaceHigh,
        surfaceHighest = surfaceHighest,
        cardBackground = cardBackground,
        outline = outline,
        outlineVariant = outlineVariant,
        priorityHigh = priorityHigh,
        priorityMedium = priorityMedium,
        priorityLow = priorityLow,
    )
}
