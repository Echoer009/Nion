package com.echonion.nion.ui.companion.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具注册中心 —— 自动加载并管理所有 Agent 可用工具。
 *
 * 设计原则：
 * - **统一接口**：8 个工具按操作维度划分，entity_type / action 作为路由键
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
     * 8 个工具按 CRUD + Move + Manage + Remember + Memory 维度划分，每个工具通过 entity_type/action 参数路由到具体操作。
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
    )

    /** 按名称索引的查找表，O(1) 查询 */
    private val index: Map<String, Tool> = all.associateBy { it.name }

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
     *     "name": "query",
     *     "description": "查询数据...",
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
