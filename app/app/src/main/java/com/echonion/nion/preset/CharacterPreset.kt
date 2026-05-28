package com.echonion.nion.preset

/**
 * 角色预设接口 —— 定义 flavor 级别的内置角色数据。
 *
 * - standard flavor: 空实现，所有属性返回 null，表示无内置角色
 * - character flavor: 类脑娘实现，提供完整的人设、头像、表情包等数据
 *
 * 首次启动时 CompanionViewModel 检查此接口，
 * 如果有预设数据且 DB 中尚无对应设置，则自动写入预设值。
 */
interface CharacterPreset {

    /** 伙伴名称，如 "类脑娘"。null 表示使用默认值 "Nion" */
    val companionName: String? get() = null

    /** 人设提示词。null 表示使用通用默认值 */
    val personaPrompt: String? get() = null

    /** 回复格式提示词。null 表示使用通用默认值 */
    val formatPrompt: String? get() = null

    /** 早安问候提示词。null 表示使用通用默认值 */
    val greetingMorning: String? get() = null

    /** 午间问候提示词。null 表示使用通用默认值 */
    val greetingNoon: String? get() = null

    /** 晚间问候提示词。null 表示使用通用默认值 */
    val greetingEvening: String? get() = null

    /** 任务提醒提示词。null 表示使用通用默认值 */
    val reminderPrompt: String? get() = null

    /** 天气预警提示词。null 表示使用通用默认值 */
    val weatherAlertPrompt: String? get() = null

    /** assets 中的头像文件路径，如 "avatar.png"。null 表示无预设头像 */
    val avatarAssetPath: String? get() = null

    /**
     * assets 中的表情包映射：tag → assets 文件路径。
     * 例如 "开心" → "stickers/happy.png"
     * 空 map 表示无预设表情包
     */
    val stickerAssets: Map<String, String> get() = emptyMap()

    companion object {
        /**
         * 获取当前 flavor 提供的 CharacterPreset 实例。
         * CharacterPresetProvider 由各 flavor 源集提供具体对象实现。
         */
        fun current(): CharacterPreset = CharacterPresetProvider.instance
    }
}
