package com.echonion.nion.ui.companion

/**
 * 斜杠命令解析器 —— 把用户在聊天输入框里以 "/" 开头的文本解析为结构化命令。
 *
 * 设计要点：
 * - 命令在 [CompanionViewModel.sendMessage] 入口拦截，纯本地执行，
 *   绝不进入 LLM Agent Loop（零 token 消耗、即时生效）。
 * - 支持中英文别名：`/隐藏` ≡ `/hide`，`/显示` ≡ `/show`。
 * - 一轮 = 一个 user 消息 + 其引发的助手回复（含工具过程）；
 *   隐藏/显示都以"轮"为单位，保证 tool_calls ↔ tool_result 配对不被切断。
 *
 * 命令格式：
 * - `/隐藏 N` / `/hide N`   → 再隐藏最早 N 轮（累加，至少保留 1 轮）
 * - `/显示 N` / `/show N`   → 放出最早 N 轮（累减，下限 0，即完全解开）
 * - `/隐藏 all` / `/hide all` → 一键隐藏到仅剩最近 1 轮（中文"全部"同义）
 * - `/显示 all` / `/show all` → 一键恢复全部已隐藏对话（完全解开）
 * - `/隐藏` / `/显示`       → 无参数时仅回显当前状态
 *
 * 未命中已知关键字、或参数非法的输入，均返回 [SlashCommand.NotACommand]，
 * 交还给普通消息流程把原文发给 AI，避免误吞用户输入。
 */
sealed class SlashCommand {
    /** 不是命令（不以已知关键字开头，或参数非法），按普通消息发给 AI */
    object NotACommand : SlashCommand()

    /**
     * 隐藏最早若干轮对话。
     *
     * @param n 要隐藏的轮数；与 [all] 互斥（二者最多一个生效）
     * @param all true 表示一键隐藏到仅剩最近 1 轮（对应 `/隐藏 all`）
     */
    data class Hide(val n: Int? = null, val all: Boolean = false) : SlashCommand()

    /**
     * 放出（恢复）最早若干轮对话。
     *
     * @param n 要放出的轮数；与 [all] 互斥（二者最多一个生效）
     * @param all true 表示一键恢复全部已隐藏对话（对应 `/显示 all`）
     */
    data class Show(val n: Int? = null, val all: Boolean = false) : SlashCommand()
}

/**
 * 命令解析单例。无状态、线程安全。
 */
object SlashCommandHandler {
    /** 命令动作类型（关键字去掉参数后的语义分类） */
    private enum class ActionType { HIDE, SHOW }

    /** 关键字 → 动作类型 的映射（中英文别名，统一小写匹配） */
    private val keywords: Map<String, ActionType> = mapOf(
        "隐藏" to ActionType.HIDE,
        "hide" to ActionType.HIDE,
        "显示" to ActionType.SHOW,
        "show" to ActionType.SHOW,
    )

    /**
     * 解析一行输入文本。
     *
     * @param text 原始输入（调用方应先 trim）
     * @return 解析结果；非 "/" 开头、关键字未命中、或参数非法时返回 [SlashCommand.NotACommand]
     */
    fun parse(text: String): SlashCommand {
        // 必须以 "/" 开头才进入命令判定
        if (!text.startsWith("/")) return SlashCommand.NotACommand
        // 去掉 "/" 后按空白拆分：第一段是命令关键字，第二段（如有）是参数
        val body = text.removePrefix("/").trim()
        if (body.isEmpty()) return SlashCommand.NotACommand
        val parts = body.split(Regex("\\s+"), limit = 2)
        val keyword = parts[0].lowercase()
        val argStr = parts.getOrNull(1)?.trim()
        // 关键字未命中 → 当普通文本发给 AI
        val action = keywords[keyword] ?: return SlashCommand.NotACommand
        // 参数解析，分三类：
        //  1. 空 → 查询状态（n=null, all=false）
        //  2. "all" / "全部"（忽略大小写）→ all=true
        //  3. ≥1 的整数 → 计数 n
        //  4. 其它 → 非法，当普通文本发给 AI，避免误吞输入
        return when {
            argStr.isNullOrEmpty() -> build(action, n = null, all = false)
            argStr.equals("all", ignoreCase = true) || argStr == "全部" ->
                build(action, n = null, all = true)
            else -> {
                val n = argStr.toIntOrNull()?.takeIf { it >= 1 }
                    ?: return SlashCommand.NotACommand
                build(action, n = n, all = false)
            }
        }
    }

    /** 按动作类型组装最终命令对象 */
    private fun build(action: ActionType, n: Int?, all: Boolean): SlashCommand = when (action) {
        ActionType.HIDE -> SlashCommand.Hide(n = n, all = all)
        ActionType.SHOW -> SlashCommand.Show(n = n, all = all)
    }

    /**
     * 命令补全条目 —— 供输入 "/" 时的 UI 提示展示。
     *
     * @property trigger 主触发词（中文，点击补全时填入输入框的内容）
     * @property aliases 别名列表（如英文 /hide），仅用于展示
     * @property description 一句话用途说明
     */
    data class CommandSuggestion(
        val trigger: String,
        val aliases: List<String>,
        val description: String,
    )

    /** 所有可用命令的补全信息（用于 UI 提示），顺序即展示顺序 */
    private val allSuggestions: List<CommandSuggestion> = listOf(
        CommandSuggestion("/隐藏", listOf("/hide"), "隐藏较早的对话轮次，例：/隐藏 5 或 /隐藏 all"),
        CommandSuggestion("/显示", listOf("/show"), "恢复已隐藏的对话，例：/显示 3 或 /显示 all"),
    )

    /**
     * 根据当前输入返回匹配的命令提示列表（用于输入 "/" 时弹出补全菜单）。
     *
     * - 输入恰好为 "/" → 返回全部命令
     * - 输入为 "/x..."（不含空格）→ 返回触发词或别名以输入为前缀（忽略大小写）的命令
     * - 一旦输入包含空格（命令词已选定、进入参数输入阶段，或已被补全）→ 返回空，提示消失
     * - 非命令输入 → 返回空
     */
    fun matchSuggestions(input: String): List<CommandSuggestion> {
        if (!input.startsWith("/")) return emptyList()
        // 含空格说明命令词阶段已结束（用户已选命令或手动输入空格），不再提示
        if (input.contains(" ")) return emptyList()
        val q = input.trim()
        if (q == "/") return allSuggestions
        return allSuggestions.filter { s ->
            (listOf(s.trigger) + s.aliases).any { it.startsWith(q, ignoreCase = true) }
        }
    }
}
