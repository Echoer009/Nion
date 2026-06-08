package com.echonion.nion.ui.companion.tools

import org.json.JSONObject
import uniffi.nion_core.NionCore

/**
 * 网页搜索工具 —— 通过 Bing 搜索互联网，获取最新信息。
 *
 * 此工具为只读工具，不修改任何本地数据，不触发 UI 刷新。
 * 搜索结果（标题、URL、摘要）以 JSON 格式回传给 LLM，由 LLM 综合分析后回复用户。
 *
 * 重要约束：Bing 对中文查询存在字典模式 bug（搜索"拼多多"会返回"拼"的字典释义），
 * 所有 URL 参数组合均无法关闭此 bug，因此搜索词必须使用英文。
 *
 * LLM 应在以下场景调用此工具：
 * - 用户询问实时事件、最新新闻
 * - 需要查询天气以外的外部知识（天气已有专门的 query entity_type=weather）
 * - 需要验证某个事实或获取最新数据
 *
 * 不需要搜索的场景（LLM 应直接回答）：
 * - 通用知识问答（如"什么是量子力学"）
 * - 数学计算、翻译等
 * - 关于用户本地任务/清单/分组的操作
 *
 * 返回 JSON 格式：
 * ```json
 * {
 *   "results": [
 *     {"title": "页面标题", "url": "https://...", "snippet": "摘要..."},
 *     ...
 *   ],
 *   "count": 5,
 *   "query": "search query"
 * }
 * ```
 */
object WebSearchTool : Tool {

    override val name = "websearch"

    override val affectsData = emptySet<DataType>()

    override val description =
        "搜索互联网获取最新信息。当用户询问实时事件、新闻、或需要查询外部知识时使用。" +
            "返回搜索结果的标题、链接和摘要，由你综合分析后回复用户。" +
            "【必须遵守】搜索词只能使用英文。无论用户用什么语言提问，你必须将关键词翻译成英文后再搜索。" +
            "绝对不要在搜索词中使用中文、日文、韩文等非拉丁字符，否则搜索将失败。" +
            "通用知识和数学计算不需要搜索。天气查询请使用 query 工具（entity_type=weather）。"

    /** 参数 Schema：只需一个搜索关键词 */
    private val schema = """
    {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "搜索关键词，必须使用英文，将中文话题翻译为英文后再搜索"
            }
        },
        "required": ["query"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    /**
     * 执行搜索。
     *
     * 从参数中提取搜索关键词，调用 [WebSearchService] 执行搜索，
     * 返回搜索结果 JSON 供 LLM 分析。
     *
     * @param params 包含 query 字段的参数对象
     * @param core NionCore 单例（此工具不使用 core，但接口要求传入）
     * @return 搜索结果 JSON 字符串
     */
    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val query = params.optString("query", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"搜索关键词不能为空","results":[],"count":0,"query":""}"""

        return WebSearchService.search(query)
    }
}
