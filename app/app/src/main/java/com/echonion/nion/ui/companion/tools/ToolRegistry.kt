package com.echonion.nion.ui.companion.tools

import com.echonion.nion.ui.companion.JsonCanonicalizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具注册中心 —— 自动加载并管理所有 Agent 可用工具。
 *
 * 设计原则：
 * - **统一接口**：9 个工具按操作维度划分，entity_type / action 作为路由键
 *   - query：查询（合并原 6 个 get_* 工具）
 *   - create：创建（合并原 create_task / create_checklist / create_group）
 *   - update：更新（合并原 update_task / update_checklist_name / update_group）
 *   - delete：删除（合并原 delete_task / delete_checklist / delete_group）
 *   - move：移动（新增，保留专注时长等数据）
 *   - manage：通用操作（设置/移除每日循环等非 CRUD 操作）
 *   - remember：记住用户偏好（add/list/remove）
 *   - memory：主动记录关于用户的事实性信息（add/list/update/remove）
 * - **多格式适配**：自动将内部 Schema 转换为 OpenAI 和 Anthropic 的 tools 格式
 *
 * 使用方式：
 * ```
 * // 获取 OpenAI 格式的 tools 参数
 * val tools = ToolRegistry.toOpenAITools()
 *
 * // 根据名称查找工具
 * val tool = ToolRegistry.get("query")
 * ```
 */
object ToolRegistry {

    /**
     * 所有已注册的工具列表。
     * 9 个工具按 CRUD + Move + Manage + Remember + Memory + Weather 维度划分，每个工具通过 entity_type/action 参数路由到具体操作。
     */
    val all: List<Tool> = listOf(
        QueryTool,
        CreateTool,
        UpdateTool,
        DeleteTool,
        MoveTool,
        ManageTool,
        RememberTool,
        MemoryTool,
        WeatherTool,
    )

    /** 按名称索引的查找表，O(1) 查询 */
    private val index: Map<String, Tool> = all.associateBy { it.name }

    /**
     * 缓存的 OpenAI 格式 tools JSON 字符串。
     * 工具列表在运行时不变，序列化结果可安全缓存，避免 JSONObject.toString() 的 key 顺序不稳定导致缓存失效。
     */
    private val cachedOpenAIToolsJson: String by lazy {
        buildStableOpenAITools()
    }

    /**
     * 缓存的 Anthropic 格式 tools JSON 字符串。
     * 同理，运行时不变，缓存后保证每次请求的 tools 序列化完全一致。
     */
    private val cachedAnthropicToolsJson: String by lazy {
        buildStableAnthropicTools()
    }

    /**
     * 根据工具名称查找已注册的工具。
     *
     * @param name 工具名称（如 "query"、"create"）
     * @return 对应的 [Tool] 实例，未找到则返回 null
     */
    fun get(name: String): Tool? = index[name]

    /**
     * 生成 OpenAI 格式的 tools 参数数组。
     *
     * 使用缓存的稳定 JSON 字符串重新解析为 JSONArray，保证每次调用的序列化结果完全一致。
     * 这对于 DeepSeek Prefix Caching 等 API 级缓存至关重要 —— tools 是请求前缀的一部分，
     * 如果 tools 的 JSON 字符串在请求间不一致，整个前缀缓存就会失效。
     *
     * 格式为 OpenAI Chat Completions API 要求的 tools 字段：
     * ```json
     * [
     *   {
     *     "type": "function",
     *     "function": {
     *       "name": "query",
     *       "description": "查询数据...",
     *       "parameters": { ... JSON Schema ... }
     *     }
     *   }
     * ]
     * ```
     *
     * @return OpenAI 格式的工具定义数组
     */
    fun toOpenAITools(): JSONArray = JSONArray(cachedOpenAIToolsJson)

    /**
     * 生成 Anthropic 格式的 tools 参数数组。
     *
     * 使用缓存的稳定 JSON 字符串重新解析，保证序列化一致性。
     *
     * 格式为 Anthropic Messages API 要求的 tools 字段：
     * ```json
     * [
     *   {
     *     "name": "query",
     *     "description": "查询数据...",
     *     "input_schema": { ... JSON Schema ... }
     *   }
     * ]
     * ```
     *
     * @return Anthropic 格式的工具定义数组
     */
    fun toAnthropicTools(): JSONArray = JSONArray(cachedAnthropicToolsJson)

    /**
     * 构建稳定的 OpenAI 格式 tools JSON 字符串。
     * 所有工具的 schema 原始字符串（从 trimIndent 解析）在运行时是固定的。
     * 使用 [JsonCanonicalizer] 对每个工具对象做 key 排序序列化，
     * 保证 tools JSON 跨进程、跨调用完全一致，命中 DeepSeek Prefix Caching。
     */
    private fun buildStableOpenAITools(): String {
        val arr = JSONArray()
        for (tool in all) {
            arr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parametersSchema())
                })
            })
        }
        // 使用规范化序列化替代 arr.toString()，避免 JSONObject 内部 HashMap 顺序不确定
        return JsonCanonicalizer.canonicalize(arr)
    }

    /**
     * 构建稳定的 Anthropic 格式 tools JSON 字符串。
     * 同理使用 [JsonCanonicalizer] 保证 key 排序一致性。
     */
    private fun buildStableAnthropicTools(): String {
        val arr = JSONArray()
        for (tool in all) {
            arr.put(JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", tool.parametersSchema())
            })
        }
        return JsonCanonicalizer.canonicalize(arr)
    }
}
