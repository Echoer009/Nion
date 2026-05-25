package com.echonion.nion.ui.companion.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具注册中心 —— 自动加载并管理所有 Agent 可用工具。
 *
 * 设计原则：
 * - **自动加载**：新增工具只需在 [all] 列表中添加一项，整个框架自动处理
 *   Schema 生成、参数校验、执行路由，无需修改其他文件
 * - **模块化**：每个工具是独立的 object，互不依赖
 * - **多格式适配**：自动将内部 Schema 转换为 OpenAI 和 Anthropic 的 tools 格式
 *
 * 使用方式：
 * ```
 * // 获取 OpenAI 格式的 tools 参数
 * val tools = ToolRegistry.toOpenAITools()
 *
 * // 根据名称查找工具
 * val tool = ToolRegistry.get("create_task")
 * ```
 */
object ToolRegistry {

    /**
     * 所有已注册的工具列表。
     *
     * 新增工具只需在此列表末尾添加一个元素，例如：
     * ```
     * all = listOf(
     *     GetTasksTool,
     *     CreateTaskTool,
     *     YourNewTool,  // <-- 新增一行即可
     * )
     * ```
     */
    val all: List<Tool> = listOf(
        // ── 任务工具 ──
        GetTasksTool,
        GetTaskTool,
        CreateTaskTool,
        UpdateTaskTool,
        DeleteTaskTool,
        GetSubtasksTool,
        // ── 清单工具 ──
        GetChecklistsTool,
        CreateChecklistTool,
        UpdateChecklistNameTool,
        DeleteChecklistTool,
        // ── 分组工具 ──
        GetGroupsTool,
        CreateGroupTool,
        UpdateGroupTool,
        DeleteGroupTool,
    )

    /** 按名称索引的查找表，O(1) 查询 */
    private val index: Map<String, Tool> = all.associateBy { it.name }

    /**
     * 根据工具名称查找已注册的工具。
     *
     * @param name 工具名称（如 "create_task"）
     * @return 对应的 [Tool] 实例，未找到则返回 null
     */
    fun get(name: String): Tool? = index[name]

    /**
     * 生成 OpenAI 格式的 tools 参数数组。
     *
     * 格式为 OpenAI Chat Completions API 要求的 tools 字段：
     * ```json
     * [
     *   {
     *     "type": "function",
     *     "function": {
     *       "name": "create_task",
     *       "description": "创建一个新任务",
     *       "parameters": { ... JSON Schema ... }
     *     }
     *   }
     * ]
     * ```
     *
     * @return OpenAI 格式的工具定义数组
     */
    fun toOpenAITools(): JSONArray = JSONArray().apply {
        for (tool in all) {
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parametersSchema())
                })
            })
        }
    }

    /**
     * 生成 Anthropic 格式的 tools 参数数组。
     *
     * 格式为 Anthropic Messages API 要求的 tools 字段：
     * ```json
     * [
     *   {
     *     "name": "create_task",
     *     "description": "创建一个新任务",
     *     "input_schema": { ... JSON Schema ... }
     *   }
     * ]
     * ```
     *
     * @return Anthropic 格式的工具定义数组
     */
    fun toAnthropicTools(): JSONArray = JSONArray().apply {
        for (tool in all) {
            put(JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", tool.parametersSchema())
            })
        }
    }
}
