package com.echonion.nion.ui.companion.tools

import org.json.JSONArray
import org.json.JSONObject
import uniffi.nion_core.ChecklistData
import uniffi.nion_core.GroupData
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
    put("group_id", task.groupId ?: JSONObject.NULL)
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

/**
 * 将 [GroupData] 序列化为 JSON 对象。
 *
 * @param group UniFFI 生成的分组数据记录
 * @return 包含完整分组信息的 JSON 对象
 */
fun groupToJson(group: GroupData): JSONObject = JSONObject().apply {
    put("id", group.id)
    put("name", group.name)
    put("checklist_id", group.checklistId)
    put("color", group.color ?: JSONObject.NULL)
    put("sort_order", group.sortOrder)
    put("created_at", group.createdAt)
}

/**
 * 将分组列表序列化为 JSON 数组。
 *
 * @param groups 分组数据列表
 * @return JSON 数组
 */
fun groupListToJson(groups: List<GroupData>): JSONArray = JSONArray().apply {
    for (g in groups) {
        put(groupToJson(g))
    }
}

// ═══════════════════════════════════════════════════════════════════
// 统一工具实现
// 设计原则：按操作维度合并，entity_type 作为路由键
// LLM 只需思考「做什么操作」+「对什么类型」+「具体参数」
// ═══════════════════════════════════════════════════════════════════

/**
 * 统一查询工具 —— 合并了原来 6 个 get_* 工具。
 *
 * 通过 entity_type 路由到不同的查询逻辑：
 * - task: 支持按 ID 查单个、按清单筛选、查子任务、查全部
 * - checklist: 返回所有清单
 * - group: 按 checklist_id 查询某清单下的分组
 *
 * Agent 场景示例：
 * - "我有哪些任务？" → entity_type="task"
 * - "学习清单下有什么？" → entity_type="task", category_id="xxx"
 * - "这个任务的子任务" → entity_type="task", parent_id="xxx"
 * - "有哪些清单？" → entity_type="checklist"
 * - "学习清单的分组" → entity_type="group", checklist_id="xxx"
 */
object QueryTool : Tool {
    override val name = "query"
    override val description = "查询数据。通过 entity_type 指定查询类型：task（任务）、checklist（清单）、group（分组）。" +
        "task 支持 id（查单个）、category_id（按清单筛选）、parent_id（查子任务）；" +
        "checklist 返回所有清单；group 需要传 checklist_id 查询指定清单下的分组。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group"],
                "description": "查询的实体类型：task=任务, checklist=清单, group=分组"
            },
            "id": {
                "type": "string",
                "description": "按 ID 查询单个实体（仅 task 有效）"
            },
            "category_id": {
                "type": "string",
                "description": "按清单 ID 筛选任务（仅 task 有效，不传则返回全部任务）"
            },
            "checklist_id": {
                "type": "string",
                "description": "查询指定清单下的分组（group 类型时必填）"
            },
            "parent_id": {
                "type": "string",
                "description": "查询指定任务的子任务（仅 task 有效）"
            }
        },
        "required": ["entity_type"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")

        return when (entityType) {
            "task" -> executeTaskQuery(params, core)
            "checklist" -> executeChecklistQuery(core)
            "group" -> executeGroupQuery(params, core)
            else -> """{"error":"不支持的 entity_type: $entityType"}"""
        }
    }

    /**
     * 任务查询路由。
     * 优先级：id（查单个）> parent_id（查子任务）> category_id（按清单筛选）> 全部
     */
    private fun executeTaskQuery(params: JSONObject, core: NionCore): String {
        val id = params.optString("id", "").takeIf { it.isNotEmpty() }
        val parentId = params.optString("parent_id", "").takeIf { it.isNotEmpty() }
        val categoryId = params.optString("category_id", "").takeIf { it.isNotEmpty() }

        // 按 ID 查询单个任务
        if (id != null) {
            val task = core.getTask(id)
            return taskToJson(task).toString()
        }

        // 查询子任务
        if (parentId != null) {
            val subtasks = core.getSubtasks(parentId)
            return JSONObject().apply {
                put("tasks", taskListToJson(subtasks))
                put("count", subtasks.size)
            }.toString()
        }

        // 按清单筛选或返回全部
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

    /** 查询所有清单 */
    private fun executeChecklistQuery(core: NionCore): String {
        val checklists = core.getChecklists()
        return JSONObject().apply {
            put("checklists", checklistListToJson(checklists))
            put("count", checklists.size)
        }.toString()
    }

    /** 查询指定清单下的分组 */
    private fun executeGroupQuery(params: JSONObject, core: NionCore): String {
        val checklistId = params.optString("checklist_id", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"查询分组时必须指定 checklist_id"}"""

        val groups = core.getGroupsByChecklist(checklistId)
        return JSONObject().apply {
            put("groups", groupListToJson(groups))
            put("count", groups.size)
        }.toString()
    }
}

/**
 * 统一创建工具 —— 合并了原来 create_task / create_checklist / create_group。
 *
 * Agent 场景示例：
 * - "帮我建一个任务" → entity_type="task", title="..."
 * - "建一个学习清单" → entity_type="checklist", name="学习"
 * - "在语文清单下建一个分组" → entity_type="group", name="数学", checklist_id="xxx"
 */
object CreateTool : Tool {
    override val name = "create"
    override val description = "创建新实体。通过 entity_type 指定类型：task（任务）、checklist（清单）、group（分组）。" +
        "task 需要 title；checklist 需要 name；group 需要 name 和 checklist_id。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group"],
                "description": "创建的实体类型"
            },
            "title": {
                "type": "string",
                "description": "任务标题（entity_type=task 时必填）"
            },
            "description": {
                "type": "string",
                "description": "任务描述（可选，仅 task）"
            },
            "priority": {
                "type": "string",
                "enum": ["low", "medium", "high"],
                "description": "任务优先级（可选，仅 task）：low=低, medium=中, high=高，默认 medium"
            },
            "due_date": {
                "type": "string",
                "description": "任务截止日期，ISO 8601 格式如 2026-06-01（可选，仅 task）"
            },
            "category_id": {
                "type": "string",
                "description": "任务所属清单 ID（可选，仅 task）"
            },
            "parent_id": {
                "type": "string",
                "description": "父任务 ID，用于创建子任务（可选，仅 task）"
            },
            "group_id": {
                "type": "string",
                "description": "任务所属分组 ID（可选，仅 task）"
            },
            "name": {
                "type": "string",
                "description": "清单或分组的名称（entity_type=checklist 或 group 时必填）"
            },
            "checklist_id": {
                "type": "string",
                "description": "分组所属的清单 ID（entity_type=group 时必填）"
            },
            "color": {
                "type": "string",
                "description": "分组颜色，十六进制格式如 #FF5722（可选，仅 group）"
            }
        },
        "required": ["entity_type"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")

        return when (entityType) {
            "task" -> executeCreateTask(params, core)
            "checklist" -> executeCreateChecklist(params, core)
            "group" -> executeCreateGroup(params, core)
            else -> """{"error":"不支持的 entity_type: $entityType"}"""
        }
    }

    /** 创建任务 */
    private fun executeCreateTask(params: JSONObject, core: NionCore): String {
        val title = params.optString("title", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"创建任务时必须指定 title"}"""

        val task = core.createTask(
            title = title,
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

    /** 创建清单 */
    private fun executeCreateChecklist(params: JSONObject, core: NionCore): String {
        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"创建清单时必须指定 name"}"""

        val checklist = core.createChecklist(name)
        return JSONObject().apply {
            put("success", true)
            put("checklist", checklistToJson(checklist))
        }.toString()
    }

    /** 创建分组 */
    private fun executeCreateGroup(params: JSONObject, core: NionCore): String {
        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"创建分组时必须指定 name"}"""
        val checklistId = params.optString("checklist_id", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"创建分组时必须指定 checklist_id"}"""

        val color = params.optString("color", "").takeIf { it.isNotEmpty() }
        val group = core.createGroup(name, checklistId, color)
        return JSONObject().apply {
            put("success", true)
            put("group", groupToJson(group))
        }.toString()
    }
}

/**
 * 统一更新工具 —— 合并了原来 update_task / update_checklist_name / update_group。
 *
 * Agent 场景示例：
 * - "把任务标为完成" → entity_type="task", id="xxx", status="done"
 * - "改一下截止日期" → entity_type="task", id="xxx", due_date="2026-12-31"
 * - "清单改名" → entity_type="checklist", id="xxx", name="新名称"
 * - "分组改颜色" → entity_type="group", id="xxx", color="#4CAF50"
 */
object UpdateTool : Tool {
    override val name = "update"
    override val description = "更新实体信息。通过 entity_type 指定类型，只传入需要修改的字段即可。" +
        "task 支持修改 title/description/priority/status/due_date/category_id/reminder/group_id；" +
        "checklist 支持修改 name；group 支持修改 name/color。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group"],
                "description": "更新的实体类型"
            },
            "id": {
                "type": "string",
                "description": "要更新的实体 ID（必填）"
            },
            "title": {
                "type": "string",
                "description": "任务新标题（仅 task）"
            },
            "description": {
                "type": "string",
                "description": "任务新描述（仅 task）"
            },
            "priority": {
                "type": "string",
                "enum": ["low", "medium", "high"],
                "description": "任务新优先级（仅 task）"
            },
            "status": {
                "type": "string",
                "enum": ["todo", "in_progress", "done"],
                "description": "任务新状态（仅 task）：todo=待办, in_progress=进行中, done=已完成"
            },
            "due_date": {
                "type": "string",
                "description": "任务新截止日期，ISO 8601 格式（仅 task）"
            },
            "category_id": {
                "type": "string",
                "description": "任务新所属清单 ID（仅 task）。注意：移动任务到其他清单请用 move 工具"
            },
            "reminder": {
                "type": "string",
                "description": "任务新提醒时间，ISO 8601 格式（仅 task）"
            },
            "group_id": {
                "type": "string",
                "description": "任务新分组 ID（仅 task）。注意：移动任务到其他分组请用 move 工具"
            },
            "name": {
                "type": "string",
                "description": "清单或分组的新名称（checklist / group）"
            },
            "color": {
                "type": "string",
                "description": "分组新颜色，十六进制格式如 #4CAF50（仅 group）"
            }
        },
        "required": ["entity_type", "id"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")
        val id = params.optString("id", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"更新操作必须指定 id"}"""

        return when (entityType) {
            "task" -> executeUpdateTask(id, params, core)
            "checklist" -> executeUpdateChecklist(id, params, core)
            "group" -> executeUpdateGroup(id, params, core)
            else -> """{"error":"不支持的 entity_type: $entityType"}"""
        }
    }

    /** 更新任务，只修改传入的字段 */
    private fun executeUpdateTask(id: String, params: JSONObject, core: NionCore): String {
        val task = core.updateTask(
            id = id,
            title = params.optString("title", "").takeIf { it.isNotEmpty() },
            description = params.optString("description", "").takeIf { it.isNotEmpty() },
            priority = params.optString("priority", "").takeIf { it.isNotEmpty() },
            status = params.optString("status", "").takeIf { it.isNotEmpty() },
            dueDate = params.optString("due_date", "").takeIf { it.isNotEmpty() },
            categoryId = params.optString("category_id", "").takeIf { it.isNotEmpty() },
            reminder = params.optString("reminder", "").takeIf { it.isNotEmpty() },
            groupId = params.optString("group_id", "").takeIf { it.isNotEmpty() },
        )
        return JSONObject().apply {
            put("success", true)
            put("task", taskToJson(task))
        }.toString()
    }

    /** 更新清单名称 */
    private fun executeUpdateChecklist(id: String, params: JSONObject, core: NionCore): String {
        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"更新清单时必须指定 name"}"""

        val checklist = core.updateChecklistName(id, name)
        return JSONObject().apply {
            put("success", true)
            put("checklist", checklistToJson(checklist))
        }.toString()
    }

    /** 更新分组名称和颜色 */
    private fun executeUpdateGroup(id: String, params: JSONObject, core: NionCore): String {
        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"更新分组时必须指定 name"}"""
        val color = params.optString("color", "").takeIf { it.isNotEmpty() }

        val group = core.updateGroup(id, name, color)
        return JSONObject().apply {
            put("success", true)
            put("group", groupToJson(group))
        }.toString()
    }
}

/**
 * 统一删除工具 —— 合并了原来 delete_task / delete_checklist / delete_group。
 *
 * Agent 场景示例：
 * - "删掉这个任务" → entity_type="task", id="xxx"
 * - "删除学习清单" → entity_type="checklist", id="xxx"
 * - "删掉语文分组" → entity_type="group", id="xxx"
 */
object DeleteTool : Tool {
    override val name = "delete"
    override val description = "永久删除实体。此操作不可撤销。" +
        "删除清单时其中的任务不会被删除（变为未分类）；" +
        "删除分组时组内任务不会被删除（移至未分组）。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group"],
                "description": "删除的实体类型"
            },
            "id": {
                "type": "string",
                "description": "要删除的实体 ID"
            }
        },
        "required": ["entity_type", "id"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")
        val id = params.optString("id", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"删除操作必须指定 id"}"""

        val deleted = when (entityType) {
            "task" -> core.deleteTask(id)
            "checklist" -> core.deleteChecklist(id)
            "group" -> core.deleteGroup(id)
            else -> return """{"error":"不支持的 entity_type: $entityType"}"""
        }

        return JSONObject().apply {
            put("success", true)
            put("deleted", deleted)
        }.toString()
    }
}

/**
 * 移动工具 —— 将实体从一个容器移动到另一个，保留所有数据（如专注时长）。
 *
 * 支持的移动操作：
 * - task → checklist：将任务移到另一个清单（自动清除旧分组归属）
 * - task → group：将任务移到另一个分组（自动继承分组的清单归属）
 * - group → checklist：将整个分组移到另一个清单（组内任务也跟随移动）
 *
 * Agent 场景示例：
 * - "把这个任务移到工作清单" → entity_type="task", target_type="checklist", target_id="xxx"
 * - "把这个任务移到语文分组" → entity_type="task", target_type="group", target_id="xxx"
 * - "把数学分组移到工作清单" → entity_type="group", target_type="checklist", target_id="xxx"
 */
object MoveTool : Tool {
    override val name = "move"
    override val description = "移动实体到新的容器，保留所有数据（如专注时长）。" +
        "支持：task→checklist（任务移到其他清单）、task→group（任务移到其他分组）、" +
        "group→checklist（分组移到其他清单，组内任务跟随）。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "group"],
                "description": "要移动的实体类型：task=任务, group=分组"
            },
            "id": {
                "type": "string",
                "description": "要移动的实体 ID"
            },
            "target_type": {
                "type": "string",
                "enum": ["checklist", "group"],
                "description": "目标容器类型：checklist=清单, group=分组"
            },
            "target_id": {
                "type": "string",
                "description": "目标容器的 ID"
            }
        },
        "required": ["entity_type", "id", "target_type", "target_id"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")
        val id = params.optString("id", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"移动操作必须指定 id"}"""
        val targetType = params.getString("target_type")
        val targetId = params.optString("target_id", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"移动操作必须指定 target_id"}"""

        return when {
            // 任务移到清单：清除旧分组归属 + 设置新 category_id
            entityType == "task" && targetType == "checklist" ->
                moveTaskToChecklist(id, targetId, core)
            // 任务移到分组：自动继承分组的清单归属
            entityType == "task" && targetType == "group" ->
                moveTaskToGroup(id, targetId, core)
            // 分组移到清单：组内任务的 category_id 同步更新
            entityType == "group" && targetType == "checklist" ->
                moveGroupToChecklist(id, targetId, core)
            // 不支持的组合
            else -> """{"error":"不支持的移动操作：$entityType → $targetType"}"""
        }
    }

    /**
     * 将任务移到另一个清单。
     * 步骤：清除旧分组归属 → 设置新 category_id
     */
    private fun moveTaskToChecklist(taskId: String, checklistId: String, core: NionCore): String {
        // 先清除旧的 group_id（分组属于旧清单，移动到新清单后不应保留旧分组）
        core.updateTaskGroup(taskId, null)
        // 设置新的 category_id
        val task = core.updateTask(
            id = taskId,
            title = null,
            description = null,
            priority = null,
            status = null,
            dueDate = null,
            categoryId = checklistId,
            reminder = null,
            groupId = null,
        )
        return JSONObject().apply {
            put("success", true)
            put("task", taskToJson(task))
            put("message", "任务已移动到新清单")
        }.toString()
    }

    /**
     * 将任务移到另一个分组。
     * 步骤：查找目标分组的 checklist_id → 同时更新 group_id 和 category_id
     */
    private fun moveTaskToGroup(taskId: String, groupId: String, core: NionCore): String {
        // 查找目标分组以获取其 checklist_id
        val group = core.getGroup(groupId)
        // 同时更新 group_id 和 category_id（继承分组的清单归属）
        val task = core.updateTask(
            id = taskId,
            title = null,
            description = null,
            priority = null,
            status = null,
            dueDate = null,
            categoryId = group.checklistId,
            reminder = null,
            groupId = groupId,
        )
        return JSONObject().apply {
            put("success", true)
            put("task", taskToJson(task))
            put("message", "任务已移动到分组「${group.name}」")
        }.toString()
    }

    /**
     * 将分组移到另一个清单。
     * Rust 层会同时更新组内任务的 category_id，保持数据一致性。
     */
    private fun moveGroupToChecklist(groupId: String, checklistId: String, core: NionCore): String {
        val group = core.moveGroupToChecklist(groupId, checklistId)
        return JSONObject().apply {
            put("success", true)
            put("group", groupToJson(group))
            put("message", "分组已移动到新清单，组内任务已同步更新")
        }.toString()
    }
}
