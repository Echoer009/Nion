package com.echonion.nion.ui.companion.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.nion_core.NionCore

/**
 * 工具可能影响的数据类别，用于自动触发 UI 刷新。
 *
 * 每个 Tool 通过 [Tool.affectsData] 声明自己可能修改的数据类别，
 * ToolExecutor 执行成功后根据声明自动触发对应订阅者的刷新。
 */
enum class DataType {
    /** 任务相关数据变更（任务、清单、分组的增删改、移动、排序） → TaskViewModel / ScheduleViewModel / FocusSetupViewModel 刷新 */
    TASK_DATA,
    /** 用户偏好变更（remember 工具的 add/remove） → CompanionViewModel.refreshPreferences() */
    PREFERENCES,
    /** 用户记忆变更（memory 工具的 add/update/remove） → CompanionViewModel.refreshMemories() */
    MEMORIES,
}

/**
 * Agent 工具接口 —— 所有可被 LLM 调用的工具必须实现此接口。
 *
 * 每个工具是一个独立模块，定义自己的：
 * - 名称和描述（供 LLM 理解工具用途）
 * - 参数 JSON Schema（用于 Pydantic 风格校验 + LLM function calling）
 * - 执行逻辑（调用 NionCore 方法并返回 JSON 结果）
 * - 影响的数据类别（用于自动触发 UI 刷新）
 *
 * 新增工具只需：
 * 1. 创建一个 object 实现 [Tool]
 * 2. 在 [ToolRegistry.all] 列表中添加一行
 * 整个框架会自动处理 Schema 生成、参数校验、执行路由、数据变更通知。
 */
interface Tool {

    /** 工具名称，对应 LLM function calling 的 function name */
    val name: String

    /** 工具描述，供 LLM 理解何时及如何使用此工具 */
    val description: String

    /**
     * 此工具执行成功后可能影响的数据类别。
     *
     * ToolExecutor 在工具执行成功后会读取此属性，自动触发对应订阅者的刷新。
     * 只读工具（如 query、weather）返回空集合，不会触发任何刷新。
     *
     * 示例：
     * - CreateTool → `setOf(DataType.TASK_DATA)`
     * - MemoryTool → `setOf(DataType.PREFERENCES, DataType.MEMORIES)`
     * - QueryTool → `emptySet()`
     */
    val affectsData: Set<DataType>

    /**
     * 参数的 JSON Schema 定义。
     *
     * 格式遵循 JSON Schema draft-07，包含 type / properties / required 字段。
     * 此 Schema 同时用于：
     * - 传递给 LLM API 的 tools 参数（自动转换为 OpenAI / Anthropic 格式）
     * - [ToolValidator] 在执行前校验参数合法性
     *
     * 示例：
     * ```json
     * {
     *   "type": "object",
     *   "properties": {
     *     "title": {"type": "string", "description": "任务标题"},
     *     "priority": {"type": "string", "enum": ["low","medium","high"]}
     *   },
     *   "required": ["title"]
     * }
     * ```
     */
    fun parametersSchema(): JSONObject

    /**
     * 执行工具逻辑。
     *
     * 调用时机：参数已通过 [ToolValidator] 校验后，由 [ToolExecutor] 调用。
     * 执行上下文：在 [Dispatchers.IO] 上运行，可安全调用 UniFFI 同步方法。
     *
     * @param params 已校验的参数，键名对应 parametersSchema() 中的 properties
     * @param core   NionCore 单例，用于调用 Rust 层 CRUD 方法
     * @return 工具执行结果的 JSON 字符串，将回传给 LLM
     */
    suspend fun execute(params: JSONObject, core: NionCore): String
}

/**
 * 工具执行结果 —— 封装工具执行的成功/失败状态。
 *
 * @property success 是否执行成功
 * @property data    成功时的 JSON 结果字符串，或失败时的错误信息
 */
data class ToolResult(
    val success: Boolean,
    val data: String,
)

/**
 * 工具执行器 —— 负责参数校验、工具路由、执行调度、数据变更通知。
 *
 * 核心职责：
 * 1. 根据 tool name 从 [ToolRegistry] 查找对应工具
 * 2. 用 [ToolValidator] 校验参数是否符合 JSON Schema
 * 3. 在 IO 线程上调用工具的 [Tool.execute] 方法
 * 4. 根据 [Tool.affectsData] 自动触发数据变更通知
 * 5. 捕获异常并格式化为错误结果
 *
 * @param core NionCore 单例，注入到每个工具的 execute 方法
 * @param onDataChanged 数据变更回调，工具执行成功后根据 [Tool.affectsData] 自动触发。
 *                       订阅者通过 debounce 合并连续事件，避免 AI 连续调用 10 个工具导致 10 次刷新。
 * @param onScheduleReminder 提醒调度回调，当工具修改了 reminder/recurrence 字段时触发，传入 task_id
 */
class ToolExecutor(
    private val core: NionCore,
    private val onDataChanged: ((affectedTypes: Set<DataType>) -> Unit)? = null,
    private val onScheduleReminder: ((taskId: String) -> Unit)? = null,
) {

    /**
     * 执行指定工具。
     *
     * 流程：查找工具 → 校验参数 → 执行 → 返回结果
     * 校验失败或执行异常都会返回 [ToolResult]（success=false），不会抛出异常。
     *
     * @param name      工具名称
     * @param arguments LLM 传来的 JSON 参数字符串
     * @return 执行结果，包含 JSON 数据或错误信息
     */
    suspend fun execute(name: String, arguments: String): ToolResult {
        // 从注册中心查找工具
        val tool = ToolRegistry.get(name)
        if (tool == null) {
            return ToolResult(
                success = false,
                data = """{"error":"未知工具: $name"}""",
            )
        }

        // 解析参数 JSON
        val params = try {
            JSONObject(arguments)
        } catch (e: Exception) {
            return ToolResult(
                success = false,
                data = """{"error":"参数 JSON 解析失败: ${e.message}"}""",
            )
        }

        // Pydantic 风格校验
        val schema = tool.parametersSchema()
        val validation = ToolValidator.validate(params, schema)
        if (!validation.isValid) {
            return ToolResult(
                success = false,
                data = """{"error":"参数校验失败: ${validation.errors.joinToString("; ")}"}""",
            )
        }

        // 在 IO 线程执行工具逻辑
        return try {
            val result = withContext(Dispatchers.IO) {
                tool.execute(params, core)
            }
            ToolResult(success = true, data = result).also {
                // 根据工具声明的 affectsData 自动触发数据变更通知
                // 订阅者通过 debounce 合并连续事件，AI 连续调用 N 个工具时只刷新一次
                if (tool.affectsData.isNotEmpty()) {
                    onDataChanged?.invoke(tool.affectsData)
                }
                // 检查是否需要重新调度提醒闹钟
                if (onScheduleReminder != null && needsReschedule(name, params)) {
                    val taskId = extractTaskId(name, params, result)
                    if (taskId != null) {
                        onScheduleReminder.invoke(taskId)
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult(
                success = false,
                data = """{"error":"工具执行异常: ${e.message}"}""",
            )
        }
    }

    /**
     * 判断工具执行是否可能影响了提醒设置，需要重新调度闹钟。
     */
    private fun needsReschedule(toolName: String, params: JSONObject): Boolean {
        return when (toolName) {
            "update" -> params.has("reminder") || params.has("recurrence_rule") || params.has("recurrence_reminder_time")
            "create" -> params.has("reminder") || params.has("recurrence_rule") || params.has("recurrence_reminder_time")
            "delete" -> true // 删除任务需要取消闹钟
            else -> false
        }
    }

    /**
     * 从工具参数或执行结果中提取任务 ID。
     */
    private fun extractTaskId(toolName: String, params: JSONObject, result: String): String? {
        // 大多数工具通过 id 或 task_id 参数指定任务
        val paramId = params.optString("id", "").takeIf { it.isNotEmpty() }
            ?: params.optString("task_id", "").takeIf { it.isNotEmpty() }
        if (paramId != null) return paramId

        // create 工具的 ID 在返回结果中
        if (toolName == "create") {
            return try {
                val json = JSONObject(result)
                json.optString("id", "").takeIf { it.isNotEmpty() }
                    ?: json.optJSONObject("task")?.optString("id", "")?.takeIf { it.isNotEmpty() }
            } catch (_: Exception) { null }
        }

        return null
    }
}
