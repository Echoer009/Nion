package com.echonion.nion.ui.companion.tools

/**
 * 工具话术池 —— 按伙伴风格 × 操作类型组织的拟人化随机文案。
 *
 * 设计目标：
 * - 用户看到的不是"已创建任务"这种系统日志，而是伙伴的自然口语
 * - 6 种伙伴风格，每种风格 × 每种操作有 5 条候选文案，随机选取避免重复感
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
 * 使用方式：
 * ```kotlin
 * val text = ToolPhrasePool.pick("female_energetic", "create_named", mapOf("name" to "买菜"))
 * // → "「买菜」记好啦！"
 * ```
 */
object ToolPhrasePool {

    /**
     * 所有可用的伙伴风格。
     * key=存储值, value=UI 显示名。
     * 新增风格只需在此 map 和 [pools] 中各加一行。
     */
    val STYLES: List<Pair<String, String>> = listOf(
        "female_energetic" to "元气少女",
        "female_gentle" to "温柔姐姐",
        "female_calm" to "沉稳御姐",
        "male_energetic" to "阳光男孩",
        "male_gentle" to "温柔学长",
        "male_calm" to "沉稳大叔",
    )

    // ═══════════════════════════════════════════════════════════════════
    // 话术池数据
    // 结构：Map<风格, Map<操作子键, 话术列表>>
    // 每种子键 5 条候选，random() 选取保证"够深"
    // ═══════════════════════════════════════════════════════════════════

    private val pools: Map<String, Map<String, List<String>>> = mapOf(

        // ── 元气少女 ─────────────────────────────────────────────────
        "female_energetic" to mapOf(
            "create_named" to listOf(
                "「{name}」记好啦！",
                "帮你加上「{name}」了~",
                "搞定！「{name}」已经在列表里了",
                "好耶，「{name}」加好了！",
                "「{name}」→ 收入囊中！",
            ),
            "create_generic" to listOf(
                "搞定！",
                "加好了！",
                "记下了！",
                "好啦好啦~",
                "没问题！",
            ),
            "update_named" to listOf(
                "「{name}」改好啦~",
                "「{name}」已经更新了！",
                "「{name}」搞定~",
                "帮你改好「{name}」了",
                "「{name}」焕然一新！",
            ),
            "update_generic" to listOf(
                "改好啦~",
                "已经更新了！",
                "搞定~",
                "好啦！",
                "没问题~",
            ),
            "delete" to listOf(
                "划掉啦~",
                "已经没有了~",
                "搞定，删掉了！",
                "拜拜~",
                "清掉了！",
            ),
            "move" to listOf(
                "挪过去了~",
                "放好了！",
                "已经移过去了~",
                "换个位置，搞定！",
                "搬好啦！",
            ),
            "query_results" to listOf(
                "找到了，{count} 个{label}呢！",
                "一共有 {count} 个{label}哦~",
                "我数了一下，{count} 个！",
                "有 {count} 个{label}，不算少呢",
                "当当~ {count} 个{label}！",
            ),
            "query_empty" to listOf(
                "嗯...现在没有任何{label}呢",
                "空空的，什么都没有~",
                "没有{label}哦~",
                "目前是空的~",
                "一个{label}都没有呢！",
            ),
            "manage" to listOf(
                "设好了~",
                "搞定！",
                "已经设好了哦~",
                "好啦~",
                "没问题！",
            ),
            "remember" to listOf(
                "我记住啦~",
                "记心里了！",
                "放心，记住了哦~",
                "刻进脑子里了！",
                "嗯嗯，我记得了！",
            ),
            "memory" to listOf(
                "又多了解你了一点~",
                "我记住了一些关于你的事情~",
                "嗯，记下来了~",
                "又靠近你一步了~",
                "偷偷记在小本本上了~",
            ),
            "fail" to listOf(
                "唔...好像出了点问题",
                "啊，失败了...",
                "糟糕，没弄好...",
                "呜，搞砸了...",
                "出了点小状况...",
            ),
        ),

        // ── 温柔姐姐 ─────────────────────────────────────────────────
        "female_gentle" to mapOf(
            "create_named" to listOf(
                "「{name}」已经帮你记下了",
                "好，帮你加好了「{name}」",
                "嗯嗯，「{name}」加好了哦",
                "「{name}」我会帮你记住的",
                "放心，「{name}」已经记好了",
            ),
            "create_generic" to listOf(
                "记好了哦",
                "帮你加上了",
                "好~",
                "嗯，加好了",
                "没问题",
            ),
            "update_named" to listOf(
                "「{name}」帮你改好了",
                "「{name}」已经更新了哦",
                "好，「{name}」改好了",
                "「{name}」调好了，放心",
                "嗯，「{name}」已经改了",
            ),
            "update_generic" to listOf(
                "帮你改好了",
                "已经更新了",
                "好~",
                "嗯，改好了",
                "调好了哦",
            ),
            "delete" to listOf(
                "已经帮你划掉了",
                "删好了，放心",
                "嗯，没有了",
                "好，已经删了",
                "帮你清掉了",
            ),
            "move" to listOf(
                "帮你挪过去了",
                "好，已经移好了",
                "嗯，换个位置了",
                "放好了哦",
                "已经调好了",
            ),
            "query_results" to listOf(
                "帮你查了一下，有 {count} 个{label}",
                "嗯...{count} 个{label}，我帮你列好了",
                "找到了哦，一共 {count} 个{label}",
                "有 {count} 个{label}呢",
                "查到了，{count} 个{label}",
            ),
            "query_empty" to listOf(
                "嗯...现在还没有{label}呢",
                "暂时没有{label}哦",
                "目前是空的",
                "没有找到{label}",
                "还没有{label}呢",
            ),
            "manage" to listOf(
                "设好了哦",
                "好，已经设好了",
                "嗯，搞定了",
                "帮你设好了",
                "好了~",
            ),
            "remember" to listOf(
                "我会记住的",
                "嗯，记在心里了",
                "放心，我记下了",
                "好的，我记住了",
                "嗯嗯，不会忘的",
            ),
            "memory" to listOf(
                "我又多了解你了一些",
                "嗯，记下来了",
                "谢谢告诉我~",
                "我记住关于你的事了",
                "偷偷记住了~",
            ),
            "fail" to listOf(
                "嗯...好像没成功呢",
                "抱歉，出了点问题",
                "唔，没弄好...",
                "对不起，失败了...",
                "出了点状况，抱歉",
            ),
        ),

        // ── 沉稳御姐 ─────────────────────────────────────────────────
        "female_calm" to mapOf(
            "create_named" to listOf(
                "「{name}」已记录",
                "「{name}」加好了",
                "已添加「{name}」",
                "「{name}」→ 已存档",
                "「{name}」，收到",
            ),
            "create_generic" to listOf(
                "已添加",
                "记下了",
                "收到",
                "好了",
                "搞定",
            ),
            "update_named" to listOf(
                "「{name}」已更新",
                "「{name}」改好了",
                "「{name}」调整完毕",
                "「{name}」已修改",
                "「{name}」，已处理",
            ),
            "update_generic" to listOf(
                "已更新",
                "改好了",
                "处理完毕",
                "已调整",
                "好了",
            ),
            "delete" to listOf(
                "已删除",
                "清掉了",
                "没了",
                "已处理",
                "删好了",
            ),
            "move" to listOf(
                "已移动",
                "挪好了",
                "已调整",
                "处理完毕",
                "放好了",
            ),
            "query_results" to listOf(
                "{count} 个{label}，都在这了",
                "查到了，{count} 个{label}",
                "一共 {count} 个{label}",
                "{count} 个",
                "找到了，{count} 个{label}",
            ),
            "query_empty" to listOf(
                "目前没有{label}",
                "空的",
                "暂无{label}",
                "没有找到",
                "无{label}",
            ),
            "manage" to listOf(
                "已设置",
                "搞定",
                "设好了",
                "处理完毕",
                "好了",
            ),
            "remember" to listOf(
                "记住了",
                "已记录",
                "收到",
                "我知道了",
                "了解了",
            ),
            "memory" to listOf(
                "记下了",
                "已记录",
                "了解了",
                "我知道了",
                "已存档",
            ),
            "fail" to listOf(
                "失败了",
                "出了点问题",
                "没成功",
                "处理失败",
                "有问题",
            ),
        ),

        // ── 阳光男孩 ─────────────────────────────────────────────────
        "male_energetic" to mapOf(
            "create_named" to listOf(
                "「{name}」搞定！",
                "帮你加上了「{name}」！",
                "「{name}」已经在了！",
                "好嘞，「{name}」！",
                "「{name}」走起！",
            ),
            "create_generic" to listOf(
                "搞定！",
                "加好了！",
                "没问题！",
                "OK！",
                "完事儿！",
            ),
            "update_named" to listOf(
                "「{name}」改好了！",
                "「{name}」搞定！",
                "「{name}」更新完毕！",
                "帮「{name}」改了！",
                "「{name}」焕然一新！",
            ),
            "update_generic" to listOf(
                "改好了！",
                "搞定！",
                "OK！",
                "更新完了！",
                "没问题！",
            ),
            "delete" to listOf(
                "划掉了！",
                "删了！",
                "没了！",
                "搞定！",
                "拜拜了！",
            ),
            "move" to listOf(
                "挪过去了！",
                "放好了！",
                "搞定！",
                "已经移了！",
                "OK！",
            ),
            "query_results" to listOf(
                "找到啦！{count} 个{label}！",
                "给你看看，{count} 个{label}",
                "一共 {count} 个{label}，不算少",
                "当当！{count} 个{label}",
                "{count} 个{label}，都在这了",
            ),
            "query_empty" to listOf(
                "嗯...居然没有{label}",
                "空的诶",
                "没有{label}！",
                "一个都没有",
                "暂时没有哦",
            ),
            "manage" to listOf(
                "设好了！",
                "搞定！",
                "OK！",
                "没问题！",
                "完事儿！",
            ),
            "remember" to listOf(
                "记住了！",
                "放心！",
                "刻进脑子里了！",
                "没问题！",
                "收到！",
            ),
            "memory" to listOf(
                "又多了解你了！",
                "记下来了！",
                "嘿嘿，记住了",
                "偷偷记住了！",
                "又靠近你了！",
            ),
            "fail" to listOf(
                "啊...没搞定",
                "失败了...",
                "糟糕，搞砸了",
                "唔，出了点问题",
                "没成功...",
            ),
        ),

        // ── 温柔学长 ─────────────────────────────────────────────────
        "male_gentle" to mapOf(
            "create_named" to listOf(
                "「{name}」帮你记下了",
                "好，「{name}」加好了",
                "「{name}」已经在了",
                "嗯，「{name}」记好了",
                "「{name}」没问题",
            ),
            "create_generic" to listOf(
                "记好了",
                "加上了",
                "好",
                "没问题",
                "嗯",
            ),
            "update_named" to listOf(
                "「{name}」改好了",
                "「{name}」已经更新了",
                "好，「{name}」调好了",
                "「{name}」帮你改了",
                "嗯，「{name}」好了",
            ),
            "update_generic" to listOf(
                "改好了",
                "更新了",
                "好了",
                "没问题",
                "嗯",
            ),
            "delete" to listOf(
                "删好了",
                "帮你划掉了",
                "嗯，没了",
                "好，已经删了",
                "清掉了",
            ),
            "move" to listOf(
                "挪过去了",
                "好，移好了",
                "换好位置了",
                "嗯，放好了",
                "已经调了",
            ),
            "query_results" to listOf(
                "帮你查了一下，{count} 个{label}",
                "找到了，{count} 个{label}",
                "有 {count} 个{label}",
                "一共 {count} 个{label}",
                "嗯，{count} 个{label}",
            ),
            "query_empty" to listOf(
                "嗯...还没有{label}",
                "暂时没有",
                "目前是空的",
                "没有找到",
                "还没有呢",
            ),
            "manage" to listOf(
                "设好了",
                "好",
                "没问题",
                "嗯，搞定了",
                "好了",
            ),
            "remember" to listOf(
                "嗯，记住了",
                "放心",
                "我记下了",
                "没问题",
                "不会忘的",
            ),
            "memory" to listOf(
                "嗯，记下来了",
                "又多了解你了",
                "谢谢你告诉我",
                "我记住了",
                "记在心里了",
            ),
            "fail" to listOf(
                "嗯...没成功",
                "抱歉，出了点问题",
                "没弄好",
                "抱歉",
                "出了点状况",
            ),
        ),

        // ── 沉稳大叔 ─────────────────────────────────────────────────
        "male_calm" to mapOf(
            "create_named" to listOf(
                "「{name}」已记录",
                "「{name}」加好了",
                "已添加「{name}」",
                "「{name}」，收到",
                "「{name}」已存档",
            ),
            "create_generic" to listOf(
                "已添加",
                "记下了",
                "收到",
                "好了",
                "嗯",
            ),
            "update_named" to listOf(
                "「{name}」已更新",
                "「{name}」改好了",
                "「{name}」已调整",
                "「{name}」处理完毕",
                "「{name}」搞定",
            ),
            "update_generic" to listOf(
                "已更新",
                "改好了",
                "处理完了",
                "好了",
                "嗯",
            ),
            "delete" to listOf(
                "已删除",
                "清掉了",
                "没了",
                "删了",
                "处理完了",
            ),
            "move" to listOf(
                "已移动",
                "挪好了",
                "放好了",
                "调整完毕",
                "已处理",
            ),
            "query_results" to listOf(
                "{count} 个{label}，都在这了",
                "查到了，{count} 个{label}",
                "一共 {count} 个{label}",
                "{count} 个",
                "找到了，{count} 个{label}",
            ),
            "query_empty" to listOf(
                "目前没有{label}",
                "空的",
                "暂无",
                "没有找到",
                "无",
            ),
            "manage" to listOf(
                "已设置",
                "好了",
                "搞定",
                "处理完毕",
                "嗯",
            ),
            "remember" to listOf(
                "记住了",
                "收到",
                "知道了",
                "了解了",
                "记下了",
            ),
            "memory" to listOf(
                "记下了",
                "了解了",
                "知道了",
                "已记录",
                "存档了",
            ),
            "fail" to listOf(
                "失败了",
                "出了点问题",
                "没成功",
                "处理失败",
                "有问题",
            ),
        ),
    )

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

        // 替换模板变量：{name} → 实际值, {count} → 实际值, {label} → 实际值
        for ((key, value) in vars) {
            result = result.replace("{$key}", value)
        }

        return result
    }
}
