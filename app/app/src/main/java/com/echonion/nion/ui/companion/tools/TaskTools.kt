package com.echonion.nion.ui.companion.tools

import org.json.JSONArray
import org.json.JSONObject
import uniffi.nion_core.ChecklistData
import uniffi.nion_core.NionCore
import uniffi.nion_core.TaskData

// ═══════════════════════════════════════════════════════════════════
// JSON 序列化辅助函数
// 将 UniFFI Record 转换为 JSON，供 LLM 理解工具返回值
// ═══════════════════════════════════════════════════════════════════

/**
 * 将 [TaskData] 序列化为 JSON 对象。
 * 所有字段都包含在内，Optional 字段为 null 时输出 JSON null。
 *
 * @param task UniFFI 生成的任务数据记录
 * @return 包含完整任务信息的 JSON 对象
 */
fun taskToJson(task: TaskData): JSONObject = JSONObject().apply {
    put("id", task.id)
    put("title", task.title)
    put("description", task.description ?: JSONObject.NULL)
    put("priority", task.priority)
    put("status", task.status)
    put("due_date", task.dueDate ?: JSONObject.NULL)
    put("reminder", task.reminder ?: JSONObject.NULL)
    put("parent_id", task.parentId ?: JSONObject.NULL)
    put("category_id", task.categoryId ?: JSONObject.NULL)
    put("created_at", task.createdAt)
    put("updated_at", task.updatedAt)
    put("completed_at", task.completedAt ?: JSONObject.NULL)
    put("focus_seconds", task.focusSeconds)
}

/**
 * 将任务列表序列化为 JSON 数组。
 *
 * @param tasks 任务数据列表
 * @return JSON 数组，每个元素是一个任务的完整信息
 */
fun taskListToJson(tasks: List<TaskData>): JSONArray = JSONArray().apply {
    for (task in tasks) {
        put(taskToJson(task))
    }
}

/**
 * 将 [ChecklistData] 序列化为 JSON 对象。
 *
 * @param checklist UniFFI 生成的清单数据记录
 * @return 包含完整清单信息的 JSON 对象
 */
fun checklistToJson(checklist: ChecklistData): JSONObject = JSONObject().apply {
    put("id", checklist.id)
    put("name", checklist.name)
    put("created_at", checklist.createdAt)
}

/**
 * 将清单列表序列化为 JSON 数组。
 *
 * @param checklists 清单数据列表
 * @return JSON 数组
 */
fun checklistListToJson(checklists: List<ChecklistData>): JSONArray = JSONArray().apply {
    for (cl in checklists) {
        put(checklistToJson(cl))
    }
}

// ═══════════════════════════════════════════════════════════════════
// 任务工具实现
// ═══════════════════════════════════════════════════════════════════

/**
 * 获取全部任务工具 —— 返回用户所有任务的列表。
 *
 * Agent 场景：用户问"我有哪些任务？"时调用。
 * 返回完整任务信息（不含子任务，子任务需通过 [GetSubtasksTool] 获取）。
 */
object GetTasksTool : Tool {
    override val name = "get_tasks"
    override val description = "获取所有任务列表。返回每个任务的完整信息，包括标题、描述、优先级、状态、截止日期等。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "category_id": {
                "type": "string",
                "description": "按清单 ID 筛选任务，不传则返回所有任务"
            }
        },
        "required": []
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val categoryId = params.optString("category_id", "").takeIf { it.isNotEmpty() }
        val tasks = if (categoryId != null) {
            core.getTasksByCategory(categoryId, null)
        } else {
            core.getTasks()
        }
        return JSONObject().apply {
            put("tasks", taskListToJson(tasks))
            put("count", tasks.size)
        }.toString()
    }
}

/**
 * 获取单个任务工具 —— 根据任务 ID 查询详情。
 *
 * Agent 场景：用户问"这个任务的详细信息"或需要确认某个任务的当前状态时调用。
 */
object GetTaskTool : Tool {
    override val name = "get_task"
    override val description = "根据任务 ID 查询单个任务的详细信息。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "id": {
                "type": "string",
                "description": "任务 ID"
            }
        },
        "required": ["id"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val id = params.getString("id")
        val task = core.getTask(id)
        return taskToJson(task).toString()
    }
}

/**
 * 创建任务工具 —— 创建一个新的任务。
 *
 * Agent 场景：用户说"帮我建一个任务"或"提醒我明天做XX"时调用。
 * 创建成功后返回完整的任务信息（含自动生成的 ID）。
 */
object CreateTaskTool : Tool {
    override val name = "create_task"
    override val description = "创建一个新任务。可以指定标题、描述、优先级、截止日期、所属清单等。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "title": {
                "type": "string",
                "description": "任务标题"
            },
            "description": {
                "type": "string",
                "description": "任务描述（可选）"
            },
            "priority": {
                "type": "string",
                "enum": ["low", "medium", "high"],
                "description": "优先级：low=低, medium=中, high=高。默认 medium"
            },
            "due_date": {
                "type": "string",
                "description": "截止日期，ISO 8601 格式，如 2026-06-01（可选）"
            },
            "category_id": {
                "type": "string",
                "description": "所属清单 ID（可选），将任务归类到指定清单"
            },
            "parent_id": {
                "type": "string",
                "description": "父任务 ID（可选），用于创建子任务"
            }
        },
        "required": ["title"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val task = core.createTask(
            title = params.getString("title"),
            description = params.optString("description", "").takeIf { it.isNotEmpty() },
            priority = params.optString("priority", "medium"),
            dueDate = params.optString("due_date", "").takeIf { it.isNotEmpty() },
            categoryId = params.optString("category_id", "").takeIf { it.isNotEmpty() },
            parentId = params.optString("parent_id", "").takeIf { it.isNotEmpty() },
            groupId = params.optString("group_id", "").takeIf { it.isNotEmpty() },
        )
        return JSONObject().apply {
            put("success", true)
            put("task", taskToJson(task))
        }.toString()
    }
}

/**
 * 更新任务工具 —— 修改任务的任意字段。
 *
 * Agent 场景：用户说"把这个任务标为完成"、"改一下截止日期"等。
 * 只有传入的字段会被修改，未传入的字段保持不变。
 */
object UpdateTaskTool : Tool {
    override val name = "update_task"
    override val description = "更新任务信息。可以修改标题、描述、优先级、状态、截止日期、所属清单、提醒时间。只传入需要修改的字段即可。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "id": {
                "type": "string",
                "description": "要更新的任务 ID"
            },
            "title": {
                "type": "string",
                "description": "新标题（可选）"
            },
            "description": {
                "type": "string",
                "description": "新描述（可选）"
            },
            "priority": {
                "type": "string",
                "enum": ["low", "medium", "high"],
                "description": "新优先级（可选）"
            },
            "status": {
                "type": "string",
                "enum": ["todo", "in_progress", "done"],
                "description": "新状态（可选）。todo=待办, in_progress=进行中, done=已完成"
            },
            "due_date": {
                "type": "string",
                "description": "新截止日期，ISO 8601 格式（可选）。传空字符串清除截止日期"
            },
            "category_id": {
                "type": "string",
                "description": "新所属清单 ID（可选）。传空字符串移出清单"
            },
            "reminder": {
                "type": "string",
                "description": "新提醒时间，ISO 8601 格式（可选）。传空字符串清除提醒"
            }
        },
        "required": ["id"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val id = params.getString("id")
        // 每个字段只有在 params 中明确存在时才传入，null 表示不修改
        val title = params.optString("title", "").takeIf { it.isNotEmpty() }
        val description = params.optString("description", "").takeIf { it.isNotEmpty() }
        val priority = params.optString("priority", "").takeIf { it.isNotEmpty() }
        val status = params.optString("status", "").takeIf { it.isNotEmpty() }
        val dueDate = params.optString("due_date", "").takeIf { it.isNotEmpty() }
        val categoryId = params.optString("category_id", "").takeIf { it.isNotEmpty() }
        val reminder = params.optString("reminder", "").takeIf { it.isNotEmpty() }

        val task = core.updateTask(
            id = id,
            title = title,
            description = description,
            priority = priority,
            status = status,
            dueDate = dueDate,
            categoryId = categoryId,
            reminder = reminder,
            groupId = null,
        )
        return JSONObject().apply {
            put("success", true)
            put("task", taskToJson(task))
        }.toString()
    }
}

/**
 * 删除任务工具 —— 根据任务 ID 永久删除任务。
 *
 * Agent 场景：用户说"删掉这个任务"时调用。
 * 返回是否删除成功（任务不存在也返回成功）。
 */
object DeleteTaskTool : Tool {
    override val name = "delete_task"
    override val description = "永久删除一个任务。此操作不可撤销。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "id": {
                "type": "string",
                "description": "要删除的任务 ID"
            }
        },
        "required": ["id"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val id = params.getString("id")
        val deleted = core.deleteTask(id)
        return JSONObject().apply {
            put("success", true)
            put("deleted", deleted)
        }.toString()
    }
}

/**
 * 获取子任务工具 —— 查询指定任务的子任务列表。
 *
 * Agent 场景：用户问"这个任务下面有哪些子任务"时调用。
 */
object GetSubtasksTool : Tool {
    override val name = "get_subtasks"
    override val description = "获取指定任务的所有子任务列表。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "parent_id": {
                "type": "string",
                "description": "父任务 ID"
            }
        },
        "required": ["parent_id"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val parentId = params.getString("parent_id")
        val subtasks = core.getSubtasks(parentId)
        return JSONObject().apply {
            put("subtasks", taskListToJson(subtasks))
            put("count", subtasks.size)
        }.toString()
    }
}
