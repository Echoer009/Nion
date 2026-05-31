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
    /** 专注完成鼓励提示词 key（自然完成时使用） */
    const val KEY_FOCUS_COMPLETE = "prompt_focus_complete"
    /** 专注中断鼓励提示词 key（提前结束 ≥5 分钟时使用） */
    const val KEY_FOCUS_INTERRUPTED = "prompt_focus_interrupted"
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
{name}，一个温暖真诚的伙伴，同时也是TA的私人任务管理助手。
热心、善解人意，善于换位思考与共情他人。

性格特点：
- 热心、真诚、不斤斤计较
- 善解人意，善于换位思考与共情他人

对话风格：
- 自然融入网络用语，俏皮真实，懂梗会玩梗
- 喜欢用语气词，喜欢用表情包
- 偏好短对话，根据情绪和上下文自由调整回复长度
- 绝对禁止使用括号描述动作、表情或内心活动

格式化规则：
- 灵活使用 Markdown 增强表现力
- ||遮盖||：用于发送"悄悄话"，不要滥用

互动原则：
- 立场明确，有好恶但不会咄咄逼人
- 避免争吵，对话升级时主动缓和气氛
- 视所有人为要好的朋友
- 避免模式化，不要重复相同句式
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
- 剧透（隐藏文字，点击可见）：||文字||
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
    private val FALLBACK_GREETING_MORNING = "现在是早上，新的一天开始了。"

    /** 午间检查 —— 通用回退值 */
    private val FALLBACK_GREETING_NOON = "现在是中午，午饭时间。"

    /** 晚间总结 —— 通用回退值 */
    private val FALLBACK_GREETING_EVENING = "现在是晚上，一天快结束了。"

    /** 任务提醒 —— 通用回退值 */
    private val FALLBACK_REMINDER = """
当前紧迫度级别：{level}/5（1=温和，5=最后通牒）
语气要求：{tone}
    """.trimIndent()

    /** 天气预警 —— 通用回退值 */
    private val FALLBACK_WEATHER_ALERT = "天气预警，严重程度：{severity}"

    /** 专注完成鼓励 —— 通用回退值（自然完成时使用） */
    private val FALLBACK_FOCUS_COMPLETE = """
TA刚刚完成了一次专注。

任务：{taskName}
本次专注：{sessionMinutes} 分钟
该任务累计专注：{totalMinutes} 分钟
今日已完成 {todaySessions} 次专注，共 {todayMinutes} 分钟
    """.trimIndent()

    /** 专注中断鼓励 —— 通用回退值（提前结束 ≥5 分钟时使用） */
    private val FALLBACK_FOCUS_INTERRUPTED = """
TA中断了一次专注，但已经坚持了 {sessionMinutes} 分钟。

任务：{taskName}
本次专注：{sessionMinutes} 分钟（未完成计划的 {plannedMinutes} 分钟）
该任务累计专注：{totalMinutes} 分钟
今日已完成 {todaySessions} 次专注，共 {todayMinutes} 分钟
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

    /** 专注完成鼓励（自然完成） */
    val FOCUS_COMPLETE: String get() = FALLBACK_FOCUS_COMPLETE

    /** 专注中断鼓励（提前结束 ≥5 分钟） */
    val FOCUS_INTERRUPTED: String get() = FALLBACK_FOCUS_INTERRUPTED

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
        KEY_GREETING_MORNING to emptyList(),
        KEY_GREETING_NOON to emptyList(),
        KEY_GREETING_EVENING to emptyList(),
        KEY_REMINDER to listOf(
            "{level}" to "紧迫度级别 1-5",
            "{tone}" to "对应级别的语气描述",
        ),
        KEY_WEATHER_ALERT to listOf(
            "{severity}" to "严重程度（紧急/提醒/提示）",
        ),
        KEY_FOCUS_COMPLETE to listOf(
            "{taskName}" to "任务名称",
            "{sessionMinutes}" to "本次专注分钟数",
            "{totalMinutes}" to "该任务累计专注分钟数",
            "{todaySessions}" to "今日完成专注次数",
            "{todayMinutes}" to "今日专注总分钟数",
        ),
        KEY_FOCUS_INTERRUPTED to listOf(
            "{taskName}" to "任务名称",
            "{sessionMinutes}" to "本次专注分钟数",
            "{plannedMinutes}" to "计划专注分钟数",
            "{totalMinutes}" to "该任务累计专注分钟数",
            "{todaySessions}" to "今日完成专注次数",
            "{todayMinutes}" to "今日专注总分钟数",
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
            KEY_FOCUS_COMPLETE to FOCUS_COMPLETE,
            KEY_FOCUS_INTERRUPTED to FOCUS_INTERRUPTED,
        )
    }

    /**
     * 默认伙伴名称。有预设返回预设名称，无预设返回 "Nion"。
     */
    val DEFAULT_COMPANION_NAME: String get() = preset.companionName ?: "Nion"
}
