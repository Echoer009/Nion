package com.echonion.nion.ui.companion.tools

import com.echonion.nion.ui.companion.JsonCanonicalizer
import com.echonion.nion.ui.companion.phoneagent.PhoneAgentTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具注册中心 —— 自动加载并管理所有 Agent 可用工具。
 *
 * 设计原则：
 * - **按页面注入**：不同导航页注入不同工具集，减少无关工具的 token 消耗
 * - **统一接口**：基础工具按操作维度划分
 *   - query：查询（task/checklist/group/weather）
 *   - create：创建（task/checklist/group，支持批量）
 *   - update：更新（task/checklist/group，支持批量）
 *   - delete：删除（task/checklist/group，支持批量）
 *   - manage：结构性操作（move 移动 + reorder 排序）
 *   - memory：记忆（scope=preference 偏好规则 / scope=fact 事实记忆）
 *   - settings：设置（主题配色控制，仅在设置页注入）
 * - **多格式适配**：自动将内部 Schema 转换为 OpenAI 和 Anthropic 的 tools 格式
 * - **Per-route 缓存**：每个路由的工具集独立缓存，切换路由时使用对应缓存，命中 API 前缀缓存
 *
 * 使用方式：
 * ```
 * // 按路由获取工具 JSON
 * val tools = ToolRegistry.toOpenAITools("settings")
 *
 * // 获取所有工具（兼容旧接口）
 * val allTools = ToolRegistry.toOpenAITools()
 *
 * // 根据名称查找工具
 * val tool = ToolRegistry.get("query")
 * ```
 */
object ToolRegistry {

    /**
     * 所有页面共享的基础工具列表。
     * 任务/清单/分组的 CRUD、移动排序、记忆管理。
     */
    val baseTools: List<Tool> = listOf(
        QueryTool,
        CreateTool,
        UpdateTool,
        DeleteTool,
        ManageTool,
        MemoryTool,
        WebSearchTool,
        PhoneAgentTool,
        ScheduledPhoneTaskTool,
    )

    /**
     * 各导航页额外注入的工具。
     * key = 路由名（与 NavHost 中的 composable route 一致），
     * value = 该页面额外注入的工具列表（叠加 baseTools）。
     *
     * 扩展新页面的专属工具只需在此 map 中添加一行。
     */
    private val pageTools: Map<String, List<Tool>> = mapOf(
        "settings" to listOf(SettingsTool),
    )

    /**
     * 所有已注册的工具列表（全部页面合集）。
     * 用于按名称查找和向后兼容。
     */
    val all: List<Tool> = baseTools + pageTools.values.flatten().distinctBy { it.name }

    /** 按名称索引的查找表，O(1) 查询 */
    private val index: Map<String, Tool> = all.associateBy { it.name }

    /**
     * Per-route 缓存的 OpenAI 格式 tools JSON。
     * key = 工具名称列表的逗号连接（如 "query,create,update,delete,manage,memory,settings"），
     * value = 规范化序列化后的稳定 JSON 字符串。
     * 每种工具组合只构建一次，后续直接从缓存取。
     */
    private val openAIToolsCache = mutableMapOf<String, String>()

    /**
     * Per-route 缓存的 Anthropic 格式 tools JSON。
     * 同理，每种工具组合缓存一份。
     */
    private val anthropicToolsCache = mutableMapOf<String, String>()

    /**
     * 根据当前导航路由返回对应的工具列表。
     *
     * @param route 当前页面路由（如 "tasks"、"settings"），null 时返回基础工具
     * @return 该页面可用的工具列表
     */
    fun toolsForRoute(route: String?): List<Tool> {
        val extras = if (route != null) pageTools[route].orEmpty() else emptyList()
        return baseTools + extras
    }

    /**
     * 根据工具名称查找已注册的工具。
     *
     * @param name 工具名称（如 "query"、"create"）
     * @return 对应的 [Tool] 实例，未找到则返回 null
     */
    fun get(name: String): Tool? = index[name]

    /**
     * 生成指定路由的 OpenAI 格式 tools 参数数组。
     *
     * 使用 per-route 缓存保证同一路由的 tools JSON 完全一致，命中 API 前缀缓存。
     * 路由切换时命中不同的缓存条目，仅首次请求未缓存时需要重新构建。
     *
     * @param route 当前页面路由，null 时返回基础工具
     * @return OpenAI 格式的工具定义数组
     */
    fun toOpenAITools(route: String? = null): JSONArray {
        val tools = toolsForRoute(route)
        val cacheKey = tools.joinToString(",") { it.name }
        val json = openAIToolsCache.getOrPut(cacheKey) {
            buildStableOpenAITools(tools)
        }
        return JSONArray(json)
    }

    /**
     * 生成指定路由的 Anthropic 格式 tools 参数数组。
     *
     * @param route 当前页面路由，null 时返回基础工具
     * @return Anthropic 格式的工具定义数组
     */
    fun toAnthropicTools(route: String? = null): JSONArray {
        val tools = toolsForRoute(route)
        val cacheKey = tools.joinToString(",") { it.name }
        val json = anthropicToolsCache.getOrPut(cacheKey) {
            buildStableAnthropicTools(tools)
        }
        return JSONArray(json)
    }

    /**
     * 构建稳定的 OpenAI 格式 tools JSON 字符串。
     * 使用 [JsonCanonicalizer] 保证 key 排序一致性，命中 API 前缀缓存。
     */
    private fun buildStableOpenAITools(tools: List<Tool>): String {
        val arr = JSONArray()
        for (tool in tools) {
            arr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parametersSchema())
                })
            })
        }
        return JsonCanonicalizer.canonicalize(arr)
    }

    /**
     * 构建稳定的 Anthropic 格式 tools JSON 字符串。
     */
    private fun buildStableAnthropicTools(tools: List<Tool>): String {
        val arr = JSONArray()
        for (tool in tools) {
            arr.put(JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", tool.parametersSchema())
            })
        }
        return JsonCanonicalizer.canonicalize(arr)
    }
}
