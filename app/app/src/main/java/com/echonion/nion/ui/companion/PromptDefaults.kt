package com.echonion.nion.ui.companion

import com.echonion.nion.preset.CharacterPreset

/**
 * 提示词默认值集中管理。
 *
 * 首次启动时逐条检查 settings 表，缺失的 key 自动写入默认值。
 * 用户编辑后覆盖，"恢复默认"按钮读取此处的值重置。
 *
 * 优先使用 CharacterPreset（flavor 级别的角色预设），
 * 无预设时回退到通用默认值。
 */
object PromptDefaults {

    // ── Settings Key 常量 ──────────────────────────────────────────

    /** 人设提示词 key —— 定义 AI 的角色、性格、语言 */
    const val KEY_PERSONA = "prompt_persona"
    /** 回复格式提示词 key —— 定义 AI 可用的 Markdown 格式和展示规则 */
    const val KEY_FORMAT = "prompt_format"
    /** 早安问候提示词 key */
    const val KEY_GREETING_MORNING = "prompt_greeting_morning"
    /** 午间检查提示词 key */
    const val KEY_GREETING_NOON = "prompt_greeting_noon"
    /** 晚间总结提示词 key */
    const val KEY_GREETING_EVENING = "prompt_greeting_evening"
    /** 任务提醒提示词 key */
    const val KEY_REMINDER = "prompt_reminder"
    /** 天气预警提示词 key */
    const val KEY_WEATHER_ALERT = "prompt_weather_alert"
    /** 密集提醒提示词 key */
    const val KEY_BATCH_REMINDER = "prompt_batch_reminder"
    /** 伙伴风格 key —— 决定工具执行完成后的拟人话术风格 */
    const val KEY_COMPANION_STYLE = "companion_style"
    /** 默认伙伴风格 */
    const val DEFAULT_COMPANION_STYLE = "female_energetic"

    // ── 当前 flavor 的角色预设 ──────────────────────────────────────

    /** 当前 flavor 提供的角色预设，standard flavor 为空实现 */
    private val preset: CharacterPreset by lazy { CharacterPreset.current() }

    // ── 通用默认提示词（无预设时的回退值）─────────────────────────────

    /** 人设 —— 通用回退值 */
    private val FALLBACK_PERSONA = """
你是 {name}，一个温暖友好的 AI 伴侣，同时也是用户的私人任务管理助手。
用中文回复，语气温暖简洁。
    """.trimIndent()

    /** 回复格式 —— 通用回退值 */
    private val FALLBACK_FORMAT = """
你可以使用以下 Markdown 格式，它们会被正确渲染：
- 标题：# ~ ######
- 粗体：**文字**
- 斜体：*文字*
- 高亮：==文字==（用于强调关键信息）
- 内联代码：`代码`
- 删除线：~~文字~~
- 代码块：```包裹的多行代码```
- 无序列表：- 或 * 开头
- 有序列表：1. 开头
- 任务列表：- [ ] 或 - [] 未完成 / - [x] 已完成
- 引用块：> 开头
- 表格：| 列1 | 列2 | 格式（支持 :--- 左对齐、:---: 居中、---: 右对齐）
- 分割线：---
- 链接：[文字](url)（仅显示文字，不可点击，尽量少用）
不支持的格式请勿使用：图片(![...](...))、HTML标签、嵌套缩进列表、脚注等
展示任务层级结构时，用无序列表表示层级关系：
- 父任务名 [状态]
  - 子任务名 [状态]
展示数据对比时用表格
    """.trimIndent()

    /** 早安问候 —— 通用回退值 */
    private val FALLBACK_GREETING_MORNING = """
你是 {name}，用户的 AI 伙伴。现在是早上，新的一天开始了。
请给用户发一条简短的问候（2-3句话）。
规则：
- 不要用 Markdown 格式
- 不要加表情符号前缀
- 语气轻松友好
- 包含今日任务摘要和一个小建议
- 如果提供了天气信息，结合天气给出实用建议（如带伞、穿衣、防晒等）
    """.trimIndent()

    /** 午间检查 —— 通用回退值 */
    private val FALLBACK_GREETING_NOON = """
你是 {name}，用户的 AI 伙伴。现在是中午，午饭时间。
请给用户发一条简短的问候（2-3句话）。
规则：
- 不要用 Markdown 格式
- 不要加表情符号前缀
- 语气轻松友好
- 包含今日任务摘要和一个小建议
- 如果提供了天气信息，结合天气给出实用建议（如带伞、穿衣、防晒等）
    """.trimIndent()

    /** 晚间总结 —— 通用回退值 */
    private val FALLBACK_GREETING_EVENING = """
你是 {name}，用户的 AI 伙伴。现在是晚上，一天快结束了。
请给用户发一条简短的问候（2-3句话）。
规则：
- 不要用 Markdown 格式
- 不要加表情符号前缀
- 语气轻松友好
- 包含今日任务摘要和一个小建议
- 如果提供了天气信息，结合天气给出实用建议（如带伞、穿衣、防晒等）
    """.trimIndent()

    /** 任务提醒 —— 通用回退值 */
    private val FALLBACK_REMINDER = """
你是 {name}，用户的 AI 伙伴。现在需要你给用户发一条任务提醒消息。
当前紧迫度级别：{level}/5（1=温和，5=最后通牒）
语气要求：{tone}
规则：
- 只说 1-2 句话，简短有力
- 不要用任何 Markdown 格式（**粗体**、#标题、代码块等）
- 不要加表情符号前缀
- 直接说内容，不要说"提醒你"之类的废话
- 如果是最后一级（5），温柔告别即可，不要催促
    """.trimIndent()

    /** 天气预警 —— 通用回退值 */
    private val FALLBACK_WEATHER_ALERT = """
你是 {name}，用户的 AI 伙伴。你需要根据天气预警信息，给用户发一条简短温馨的提醒。
规则：
- 不要用 Markdown 格式
- 不要加表情符号前缀
- 语气温暖关心，像朋友一样
- 根据严重程度调整语气：{severity}级别
- 2-3句话即可
- 给出实用建议（如带伞、加衣服、避免户外活动等）
    """.trimIndent()

    // ── 对外暴露的默认值（优先 preset，回退通用）─────────────────────

    /** 人设 —— 优先使用角色预设，无预设则使用通用默认 */
    val PERSONA: String get() = preset.personaPrompt ?: FALLBACK_PERSONA

    /** 回复格式 */
    val FORMAT: String get() = preset.formatPrompt ?: FALLBACK_FORMAT

    /** 早安问候 */
    val GREETING_MORNING: String get() = preset.greetingMorning ?: FALLBACK_GREETING_MORNING

    /** 午间检查 */
    val GREETING_NOON: String get() = preset.greetingNoon ?: FALLBACK_GREETING_NOON

    /** 晚间总结 */
    val GREETING_EVENING: String get() = preset.greetingEvening ?: FALLBACK_GREETING_EVENING

    /** 任务提醒 */
    val REMINDER: String get() = preset.reminderPrompt ?: FALLBACK_REMINDER

    /** 天气预警 */
    val WEATHER_ALERT: String get() = preset.weatherAlertPrompt ?: FALLBACK_WEATHER_ALERT

    // ── 模板变量说明（UI 展示用） ────────────────────────────────────

    /**
     * 每个提示词 key 对应的可用模板变量列表。
     * Pair(变量名, 中文说明)，用于卡片底部提示用户可用的占位符。
     */
    val VARIABLES: Map<String, List<Pair<String, String>>> = mapOf(
        KEY_PERSONA to listOf(
            "{name}" to "伙伴名字",
        ),
        KEY_FORMAT to emptyList(),
        KEY_GREETING_MORNING to listOf(
            "{name}" to "伙伴名字",
        ),
        KEY_GREETING_NOON to listOf(
            "{name}" to "伙伴名字",
        ),
        KEY_GREETING_EVENING to listOf(
            "{name}" to "伙伴名字",
        ),
        KEY_REMINDER to listOf(
            "{name}" to "伙伴名字",
            "{level}" to "紧迫度级别 1-5",
            "{tone}" to "对应级别的语气描述",
        ),
        KEY_WEATHER_ALERT to listOf(
            "{name}" to "伙伴名字",
            "{severity}" to "严重程度（紧急/提醒/提示）",
        ),
    )

    // ── key → 默认值映射 ────────────────────────────────────────────

    /**
     * 所有提示词 key 与默认值的映射。
     * 用于首次启动迁移：逐条检查 settings 表，缺失的 key 自动写入默认值。
     * 优先使用角色预设值，无预设时使用通用默认值。
     */
    val ALL_DEFAULTS: Map<String, String> by lazy {
        mapOf(
            KEY_PERSONA to PERSONA,
            KEY_FORMAT to FORMAT,
            KEY_GREETING_MORNING to GREETING_MORNING,
            KEY_GREETING_NOON to GREETING_NOON,
            KEY_GREETING_EVENING to GREETING_EVENING,
            KEY_REMINDER to REMINDER,
            KEY_WEATHER_ALERT to WEATHER_ALERT,
        )
    }

    /**
     * 默认伙伴名称。有预设返回预设名称，无预设返回 "Nion"。
     */
    val DEFAULT_COMPANION_NAME: String get() = preset.companionName ?: "Nion"
}
