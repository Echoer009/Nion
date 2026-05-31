package com.echonion.nion.ui.companion.tools

/**
 * 工具话术池 —— 按伙伴风格 x 操作类型组织的拟人化随机文案。
 *
 * 设计目标：
 * - 用户看到的不是"已创建任务"这种系统日志，而是伙伴的自然口语
 * - 6 种伙伴风格，每种风格 x 每种操作有 5 条候选文案，随机选取避免重复感
 * - 支持模板变量替换：{name}=实体名, {count}=数量, {label}=实体类型中文名
 *
 * 操作子键说明：
 * - create_named / create_generic   创建操作（有/无具体名字）
 * - update_named / update_generic   更新操作（有/无具体名字）
 * - delete                          删除操作
 * - move                            移动操作
 * - query_results / query_empty     查询操作（有/无结果）
 * - manage                          管理操作（设置循环等）
 * - remember                        记录用户偏好
 * - memory                          记忆用户信息
 * - fail                            操作失败兜底
 *
 * 数据按性别拆分到独立文件：
 * - [ToolPhrasesFemale.kt] 女性风格（元气少女、温柔姐姐、沉稳御姐）
 * - [ToolPhrasesMale.kt] 男性风格（阳光男孩、温柔学长、沉稳大叔）
 *
 * 使用方式：
 * ```kotlin
 * val text = ToolPhrasePool.pick("female_energetic", "create_named", mapOf("name" to "买菜"))
 * // -> "「买菜」记好啦！"
 * ```
 */
object ToolPhrasePool {

    /**
     * 所有可用的伙伴风格。
     * key=存储值, value=UI 显示名。
     * 新增风格只需在此 map 和对应话术文件中各加一行。
     */
    val STYLES: List<Pair<String, String>> = listOf(
        "female_energetic" to "元气少女",
        "female_gentle" to "温柔姐姐",
        "female_calm" to "沉稳御姐",
        "male_energetic" to "阳光男孩",
        "male_gentle" to "温柔学长",
        "male_calm" to "沉稳大叔",
    )

    // 合并女性和男性风格的话术池数据
    private val pools: Map<String, Map<String, List<String>>> = femalePhrasePools + malePhrasePools

    /**
     * 根据伙伴风格和操作子键随机选取一条话术，替换模板变量后返回。
     *
     * @param style  伙伴风格代号（如 "female_energetic"）
     * @param subKey 操作子键（如 "create_named"、"query_results"、"fail"）
     * @param vars   模板变量映射，key=变量名（不含花括号），value=替换值
     * @return 替换后的最终文案。找不到匹配时返回 "操作完成" / "操作失败" 兜底
     */
    fun pick(style: String, subKey: String, vars: Map<String, String> = emptyMap()): String {
        // 从指定风格中查找话术列表，找不到则回退到默认风格
        val phrases = pools[style]?.get(subKey)
            ?: pools["female_energetic"]?.get(subKey)
            ?: return if (subKey == "fail") "操作失败" else "操作完成"

        // 随机选取一条
        var result = phrases.random()

        // 替换模板变量：{name} -> 实际值, {count} -> 实际值, {label} -> 实际值
        for ((key, value) in vars) {
            result = result.replace("{$key}", value)
        }

        return result
    }
}
