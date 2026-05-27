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
    put("reminder", task.reminder ?: JSONObject.NULL)
    put("parent_id", task.parentId ?: JSONObject.NULL)
    put("category_id", task.categoryId ?: JSONObject.NULL)
    put("group_id", task.groupId ?: JSONObject.NULL)
    put("created_at", task.createdAt)
    put("updated_at", task.updatedAt)
    put("completed_at", task.completedAt ?: JSONObject.NULL)
    put("focus_seconds", task.focusSeconds)
    put("recurrence_rule", task.recurrenceRule ?: JSONObject.NULL)
    put("recurrence_reminder_time", task.recurrenceReminderTime ?: JSONObject.NULL)
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
 * 支持批量创建：传 items 数组可一次创建多个实体，减少 API 往返次数。
 *
 * Agent 场景示例：
 * - "帮我建一个任务" → entity_type="task", title="..."
 * - "帮我建三个任务：买牛奶、跑步、读书" → entity_type="task", items=[{"title":"买牛奶"},{"title":"跑步"},{"title":"读书"}]
 * - "建一个学习清单" → entity_type="checklist", name="学习"
 * - "在语文清单下建一个分组" → entity_type="group", name="数学", checklist_id="xxx"
 */
object CreateTool : Tool {
    override val name = "create"
    override val description = "创建新实体，支持批量。通过 entity_type 指定类型：task（任务）、checklist（清单）、group（分组）。" +
        "单个创建传 title/name 等字段；批量创建传 items 数组，每项包含对应实体所需的字段。" +
        "task 需 title，支持 reminder（一次性提醒，ISO 8601 如 2026-06-01T15:00）、" +
        "recurrence_rule（循环规则，传\"daily\"=每天循环）、recurrence_reminder_time（提醒时间 HH:MM）；" +
        "checklist 需 name；group 需 name 和 checklist_id。"

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
                "description": "任务标题（entity_type=task 且非批量时必填）"
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
            "recurrence_rule": {
                "type": "string",
                "enum": ["daily"],
                "description": "循环规则（可选，仅 task）。不传或传 null 表示不循环，传\"daily\"表示每天循环"
            },
            "recurrence_reminder_time": {
                "type": "string",
                "description": "每日循环提醒时间，格式 HH:MM，如 09:00，精确到分钟（仅 task，需搭配 recurrence_rule 使用）"
            },
            "reminder": {
                "type": "string",
                "description": "一次性提醒时间，ISO 8601 格式如 2026-06-01T15:00（可选，仅 task）。设置后 Nion 会在该时间主动提醒用户"
            },
            "name": {
                "type": "string",
                "description": "清单或分组的名称（entity_type=checklist 或 group 且非批量时必填）"
            },
            "checklist_id": {
                "type": "string",
                "description": "分组所属的清单 ID（entity_type=group 时必填）"
            },
            "color": {
                "type": "string",
                "description": "分组颜色，十六进制格式如 #FF5722（可选，仅 group）"
            },
            "items": {
                "type": "array",
                "description": "批量创建时的实体列表。每项是一个对象，包含该实体类型所需的字段（task 需 title，checklist 需 name，group 需 name+checklist_id）。批量创建时不需要传 title/name 等单字段参数",
                "items": {
                    "type": "object",
                    "description": "单个实体的创建参数对象"
                }
            }
        },
        "required": ["entity_type"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")
        val itemsArray = params.optJSONArray("items")

        // 批量创建模式：items 不为空时走批量逻辑
        if (itemsArray != null && itemsArray.length() > 0) {
            return executeBatchCreate(entityType, itemsArray, core)
        }

        // 单个创建模式（向后兼容）
        return when (entityType) {
            "task" -> executeCreateTask(params, core)
            "checklist" -> executeCreateChecklist(params, core)
            "group" -> executeCreateGroup(params, core)
            else -> """{"error":"不支持的 entity_type: $entityType"}"""
        }
    }

    /**
     * 批量创建实体。
     * 遍历 items 数组，对每项调用对应的单条创建逻辑，收集结果。
     * 部分失败时返回成功和失败各自的计数及错误详情，不会中断整个批次。
     */
    private fun executeBatchCreate(entityType: String, items: org.json.JSONArray, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0

        for (i in 0 until items.length()) {
            val itemParams = items.getJSONObject(i)
            try {
                when (entityType) {
                    "task" -> {
                        val title = itemParams.optString("title", "").takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("批量创建任务时每项必须包含 title，第 ${i + 1} 项缺失")
                        val task = core.createTask(
                            title = title,
                            description = itemParams.optString("description", "").takeIf { it.isNotEmpty() },
                            priority = itemParams.optString("priority", "medium"),
                            categoryId = itemParams.optString("category_id", "").takeIf { it.isNotEmpty() },
                            parentId = itemParams.optString("parent_id", "").takeIf { it.isNotEmpty() },
                            groupId = itemParams.optString("group_id", "").takeIf { it.isNotEmpty() },
                            recurrenceRule = itemParams.optString("recurrence_rule", "").takeIf { it.isNotEmpty() },
                            recurrenceReminderTime = itemParams.optString("recurrence_reminder_time", "").takeIf { it.isNotEmpty() },
                        )
                        // 批量创建也支持 reminder 字段
                        val reminder = itemParams.optString("reminder", "").takeIf { it.isNotEmpty() }
                        val finalTask = if (reminder != null) {
                            core.updateTask(task.id, null, null, null, null, null, reminder, null, null, null)
                        } else task
                        results.put(JSONObject().apply {
                            put("index", i)
                            put("success", true)
                            put("task", taskToJson(finalTask))
                        })
                        successCount++
                    }
                    "checklist" -> {
                        val name = itemParams.optString("name", "").takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("批量创建清单时每项必须包含 name，第 ${i + 1} 项缺失")
                        val checklist = core.createChecklist(name)
                        results.put(JSONObject().apply {
                            put("index", i)
                            put("success", true)
                            put("checklist", checklistToJson(checklist))
                        })
                        successCount++
                    }
                    "group" -> {
                        val name = itemParams.optString("name", "").takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("批量创建分组时每项必须包含 name，第 ${i + 1} 项缺失")
                        val checklistId = itemParams.optString("checklist_id", "").takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("批量创建分组时每项必须包含 checklist_id，第 ${i + 1} 项缺失")
                        val color = itemParams.optString("color", "").takeIf { it.isNotEmpty() }
                        val group = core.createGroup(name, checklistId, color)
                        results.put(JSONObject().apply {
                            put("index", i)
                            put("success", true)
                            put("group", groupToJson(group))
                        })
                        successCount++
                    }
                    else -> throw IllegalArgumentException("不支持的批量创建类型: $entityType")
                }
            } catch (e: Exception) {
                results.put(JSONObject().apply {
                    put("index", i)
                    put("success", false)
                    put("error", e.message ?: "未知错误")
                })
                failCount++
            }
        }

        return JSONObject().apply {
            put("success", failCount == 0)
            put("entity_type", entityType)
            put("success_count", successCount)
            put("fail_count", failCount)
            put("results", results)
        }.toString()
    }

    /** 创建任务 */
    private fun executeCreateTask(params: JSONObject, core: NionCore): String {
        val title = params.optString("title", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"创建任务时必须指定 title"}"""

        val task = core.createTask(
            title = title,
            description = params.optString("description", "").takeIf { it.isNotEmpty() },
            priority = params.optString("priority", "medium"),
            categoryId = params.optString("category_id", "").takeIf { it.isNotEmpty() },
            parentId = params.optString("parent_id", "").takeIf { it.isNotEmpty() },
            groupId = params.optString("group_id", "").takeIf { it.isNotEmpty() },
            recurrenceRule = params.optString("recurrence_rule", "").takeIf { it.isNotEmpty() },
            recurrenceReminderTime = params.optString("recurrence_reminder_time", "").takeIf { it.isNotEmpty() },
        )
        // 如果指定了 reminder，需要二次更新（createTask 不支持 reminder 参数）
        val reminder = params.optString("reminder", "").takeIf { it.isNotEmpty() }
        val finalTask = if (reminder != null) {
            core.updateTask(task.id, null, null, null, null, null, reminder, null, null, null)
        } else task
        return JSONObject().apply {
            put("success", true)
            put("task", taskToJson(finalTask))
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
 * 支持批量更新：传 ids 数组可一次更新多个同类型实体，所有实体应用相同的变更。
 *
 * Agent 场景示例：
 * - "把任务标为完成" → entity_type="task", id="xxx", status="done"
 * - "把这三个任务都标为完成" → entity_type="task", ids=["id1","id2","id3"], status="done"
     * - "改一下提醒时间" → entity_type="task", id="xxx", reminder="2026-12-31T09:00"
 * - "清单改名" → entity_type="checklist", id="xxx", name="新名称"
 * - "分组改颜色" → entity_type="group", id="xxx", color="#4CAF50"
 */
object UpdateTool : Tool {
    override val name = "update"
    override val description = "更新实体信息，支持批量。通过 entity_type 指定类型，只传入需要修改的字段即可。" +
        "单个更新传 id；批量更新传 ids 数组，所有实体应用相同的变更字段。" +
        "task 支持修改 title/description/priority/status/category_id/reminder/group_id/" +
        "recurrence_rule（daily=每天循环）/recurrence_reminder_time（HH:MM 提醒时间）；" +
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
                "description": "单个更新时的实体 ID（与 ids 二选一）"
            },
            "ids": {
                "type": "array",
                "description": "批量更新时的实体 ID 列表（与 id 二选一），所有实体应用相同的变更字段",
                "items": { "type": "string" }
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
            "recurrence_rule": {
                "type": "string",
                "enum": ["daily"],
                "description": "循环规则（仅 task）。传\"daily\"表示每天循环，传 null 表示不循环"
            },
            "recurrence_reminder_time": {
                "type": "string",
                "description": "每日循环提醒时间，格式 HH:MM 如 09:00（仅 task，精确到分钟）"
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
        "required": ["entity_type"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")
        val singleId = params.optString("id", "").takeIf { it.isNotEmpty() }
        val idsArray = params.optJSONArray("ids")

        // 批量更新模式
        if (idsArray != null && idsArray.length() > 0) {
            return executeBatchUpdate(entityType, idsArray, params, core)
        }

        // 单个更新模式（向后兼容）
        if (singleId == null) return """{"error":"更新操作必须指定 id 或 ids"}"""

        return when (entityType) {
            "task" -> executeUpdateTask(singleId, params, core)
            "checklist" -> executeUpdateChecklist(singleId, params, core)
            "group" -> executeUpdateGroup(singleId, params, core)
            else -> """{"error":"不支持的 entity_type: $entityType"}"""
        }
    }

    /**
     * 批量更新实体。
     * 遍历 ids 数组，对每个 ID 调用对应的单条更新逻辑，收集结果。
     */
    private fun executeBatchUpdate(entityType: String, ids: org.json.JSONArray, params: JSONObject, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0

        for (i in 0 until ids.length()) {
            val id = ids.getString(i)
            try {
                when (entityType) {
                    "task" -> {
                        val task = core.updateTask(
                            id = id,
                            title = params.optString("title", "").takeIf { it.isNotEmpty() },
                            description = params.optString("description", "").takeIf { it.isNotEmpty() },
                            priority = params.optString("priority", "").takeIf { it.isNotEmpty() },
                            status = params.optString("status", "").takeIf { it.isNotEmpty() },
                            categoryId = params.optString("category_id", "").takeIf { it.isNotEmpty() },
                            reminder = params.optString("reminder", "").takeIf { it.isNotEmpty() },
                            groupId = params.optString("group_id", "").takeIf { it.isNotEmpty() },
                            recurrenceRule = params.optString("recurrence_rule", "").takeIf { it.isNotEmpty() },
                            recurrenceReminderTime = params.optString("recurrence_reminder_time", "").takeIf { it.isNotEmpty() },
                        )
                        results.put(JSONObject().apply {
                            put("id", id)
                            put("success", true)
                            put("task", taskToJson(task))
                        })
                        successCount++
                    }
                    "checklist" -> {
                        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("更新清单时必须指定 name")
                        val checklist = core.updateChecklistName(id, name)
                        results.put(JSONObject().apply {
                            put("id", id)
                            put("success", true)
                            put("checklist", checklistToJson(checklist))
                        })
                        successCount++
                    }
                    "group" -> {
                        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("更新分组时必须指定 name")
                        val color = params.optString("color", "").takeIf { it.isNotEmpty() }
                        val group = core.updateGroup(id, name, color)
                        results.put(JSONObject().apply {
                            put("id", id)
                            put("success", true)
                            put("group", groupToJson(group))
                        })
                        successCount++
                    }
                    else -> throw IllegalArgumentException("不支持的批量更新类型: $entityType")
                }
            } catch (e: Exception) {
                results.put(JSONObject().apply {
                    put("id", id)
                    put("success", false)
                    put("error", e.message ?: "未知错误")
                })
                failCount++
            }
        }

        return JSONObject().apply {
            put("success", failCount == 0)
            put("entity_type", entityType)
            put("success_count", successCount)
            put("fail_count", failCount)
            put("results", results)
        }.toString()
    }

    /** 更新任务，只修改传入的字段 */
    private fun executeUpdateTask(id: String, params: JSONObject, core: NionCore): String {
        val task = core.updateTask(
            id = id,
            title = params.optString("title", "").takeIf { it.isNotEmpty() },
            description = params.optString("description", "").takeIf { it.isNotEmpty() },
            priority = params.optString("priority", "").takeIf { it.isNotEmpty() },
            status = params.optString("status", "").takeIf { it.isNotEmpty() },
            categoryId = params.optString("category_id", "").takeIf { it.isNotEmpty() },
            reminder = params.optString("reminder", "").takeIf { it.isNotEmpty() },
            groupId = params.optString("group_id", "").takeIf { it.isNotEmpty() },
            recurrenceRule = params.optString("recurrence_rule", "").takeIf { it.isNotEmpty() },
            recurrenceReminderTime = params.optString("recurrence_reminder_time", "").takeIf { it.isNotEmpty() },
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
 * 支持批量删除：传 ids 数组可一次删除多个同类型实体。
 *
 * Agent 场景示例：
 * - "删掉这个任务" → entity_type="task", id="xxx"
 * - "删掉这三个任务" → entity_type="task", ids=["id1","id2","id3"]
 * - "删除学习清单" → entity_type="checklist", id="xxx"
 * - "删掉语文分组" → entity_type="group", id="xxx"
 */
object DeleteTool : Tool {
    override val name = "delete"
    override val description = "永久删除实体，支持批量。此操作不可撤销。" +
        "单个删除传 id；批量删除传 ids 数组。" +
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
                "description": "单个删除时的实体 ID（与 ids 二选一）"
            },
            "ids": {
                "type": "array",
                "description": "批量删除时的实体 ID 列表（与 id 二选一）",
                "items": { "type": "string" }
            }
        },
        "required": ["entity_type"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")
        val singleId = params.optString("id", "").takeIf { it.isNotEmpty() }
        val idsArray = params.optJSONArray("ids")

        // 批量删除模式
        if (idsArray != null && idsArray.length() > 0) {
            return executeBatchDelete(entityType, idsArray, core)
        }

        // 单个删除模式（向后兼容）
        if (singleId == null) return """{"error":"删除操作必须指定 id 或 ids"}"""

        val deleted = when (entityType) {
            "task" -> core.deleteTask(singleId)
            "checklist" -> core.deleteChecklist(singleId)
            "group" -> core.deleteGroup(singleId)
            else -> return """{"error":"不支持的 entity_type: $entityType"}"""
        }

        return JSONObject().apply {
            put("success", true)
            put("deleted", deleted)
        }.toString()
    }

    /**
     * 批量删除实体。
     * 遍历 ids 数组，逐个删除并收集结果。
     */
    private fun executeBatchDelete(entityType: String, ids: org.json.JSONArray, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0

        for (i in 0 until ids.length()) {
            val id = ids.getString(i)
            try {
                when (entityType) {
                    "task" -> core.deleteTask(id)
                    "checklist" -> core.deleteChecklist(id)
                    "group" -> core.deleteGroup(id)
                    else -> throw IllegalArgumentException("不支持的批量删除类型: $entityType")
                }
                results.put(JSONObject().apply {
                    put("id", id)
                    put("success", true)
                })
                successCount++
            } catch (e: Exception) {
                results.put(JSONObject().apply {
                    put("id", id)
                    put("success", false)
                    put("error", e.message ?: "未知错误")
                })
                failCount++
            }
        }

        return JSONObject().apply {
            put("success", failCount == 0)
            put("entity_type", entityType)
            put("success_count", successCount)
            put("fail_count", failCount)
            put("results", results)
        }.toString()
    }
}

/**
 * 移动工具 —— 将实体从一个容器移动到另一个，保留所有数据（如专注时长）。
 *
 * 支持批量移动：传 ids 数组可一次移动多个同类型实体到同一目标。
 *
 * 支持的移动操作：
 * - task → checklist：将任务移到另一个清单（自动清除旧分组归属）
 * - task → group：将任务移到另一个分组（自动继承分组的清单归属）
 * - group → checklist：将整个分组移到另一个清单（组内任务也跟随移动）
 *
 * Agent 场景示例：
 * - "把这个任务移到工作清单" → entity_type="task", id="xxx", target_type="checklist", target_id="xxx"
 * - "把这三个任务都移到语文分组" → entity_type="task", ids=["id1","id2","id3"], target_type="group", target_id="xxx"
 * - "把数学分组移到工作清单" → entity_type="group", id="xxx", target_type="checklist", target_id="xxx"
 */
object MoveTool : Tool {
    override val name = "move"
    override val description = "移动实体到新的容器，保留所有数据（如专注时长），支持批量。" +
        "单个移动传 id；批量移动传 ids 数组，所有实体移到同一目标。" +
        "支持：task→checklist（任务移到其他清单）、task→group（任务移到其他分组）、" +
        "task→task（任务成为另一任务的子任务）、task→root（提升子任务为独立主任务）、" +
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
                "description": "单个移动时的实体 ID（与 ids 二选一）"
            },
            "ids": {
                "type": "array",
                "description": "批量移动时的实体 ID 列表（与 id 二选一），所有实体移到同一目标",
                "items": { "type": "string" }
            },
            "target_type": {
                "type": "string",
                "enum": ["checklist", "group", "task", "root"],
                "description": "目标类型：checklist=清单, group=分组, task=成为目标任务的子任务, root=提升为独立主任务"
            },
            "target_id": {
                "type": "string",
                "description": "目标容器/任务的 ID（root 类型不需要此字段）"
            }
        },
        "required": ["entity_type", "target_type"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")
        val singleId = params.optString("id", "").takeIf { it.isNotEmpty() }
        val idsArray = params.optJSONArray("ids")
        val targetType = params.getString("target_type")
        // root 类型不需要 target_id，其他类型必填
        val targetId = params.optString("target_id", "").takeIf { it.isNotEmpty() }
            ?: if (targetType != "root") return """{"error":"移动操作必须指定 target_id"}""" else ""

        // 批量移动模式
        if (idsArray != null && idsArray.length() > 0) {
            return executeBatchMove(entityType, idsArray, targetType, targetId, core)
        }

        // 单个移动模式（向后兼容）
        if (singleId == null) return """{"error":"移动操作必须指定 id 或 ids"}"""

        return executeSingleMove(entityType, singleId, targetType, targetId, core)
    }

    /** 单个移动路由 */
    private fun executeSingleMove(entityType: String, id: String, targetType: String, targetId: String, core: NionCore): String = when {
        entityType == "task" && targetType == "checklist" -> moveTaskToChecklist(id, targetId, core)
        entityType == "task" && targetType == "group" -> moveTaskToGroup(id, targetId, core)
        entityType == "task" && targetType == "task" -> moveTaskToParent(id, targetId, core)
        entityType == "task" && targetType == "root" -> promoteTaskToRoot(id, core)
        entityType == "group" && targetType == "checklist" -> moveGroupToChecklist(id, targetId, core)
        else -> """{"error":"不支持的移动操作：$entityType → $targetType"}"""
    }

    /**
     * 批量移动实体。
     * 遍历 ids 数组，逐个移动到同一目标，收集结果。
     */
    private fun executeBatchMove(entityType: String, ids: org.json.JSONArray, targetType: String, targetId: String, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0

        for (i in 0 until ids.length()) {
            val id = ids.getString(i)
            try {
                when {
                    entityType == "task" && targetType == "checklist" -> {
                        val result = moveTaskToChecklist(id, targetId, core)
                        results.put(JSONObject(result).apply {
                            put("id", id)
                            put("success", true)
                        })
                        successCount++
                    }
                    entityType == "task" && targetType == "group" -> {
                        val result = moveTaskToGroup(id, targetId, core)
                        results.put(JSONObject(result).apply {
                            put("id", id)
                            put("success", true)
                        })
                        successCount++
                    }
                    entityType == "group" && targetType == "checklist" -> {
                        val result = moveGroupToChecklist(id, targetId, core)
                        results.put(JSONObject(result).apply {
                            put("id", id)
                            put("success", true)
                        })
                        successCount++
                    }
                    entityType == "task" && targetType == "task" -> {
                        val result = moveTaskToParent(id, targetId, core)
                        results.put(JSONObject(result).apply {
                            put("id", id)
                            put("success", true)
                        })
                        successCount++
                    }
                    entityType == "task" && targetType == "root" -> {
                        val result = promoteTaskToRoot(id, core)
                        results.put(JSONObject(result).apply {
                            put("id", id)
                            put("success", true)
                        })
                        successCount++
                    }
                    else -> throw IllegalArgumentException("不支持的批量移动操作：$entityType → $targetType")
                }
            } catch (e: Exception) {
                results.put(JSONObject().apply {
                    put("id", id)
                    put("success", false)
                    put("error", e.message ?: "未知错误")
                })
                failCount++
            }
        }

        return JSONObject().apply {
            put("success", failCount == 0)
            put("entity_type", entityType)
            put("target_type", targetType)
            put("target_id", targetId)
            put("success_count", successCount)
            put("fail_count", failCount)
            put("results", results)
        }.toString()
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
            categoryId = checklistId,
            reminder = null,
            groupId = null,
            recurrenceRule = null,
            recurrenceReminderTime = null,
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
            categoryId = group.checklistId,
            reminder = null,
            groupId = groupId,
            recurrenceRule = null,
            recurrenceReminderTime = null,
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

    /**
     * 将任务移到另一个任务下，成为其子任务。
     * Rust 层 updateTaskParent 会自动继承新父任务的 category_id/group_id 并级联子孙。
     * 校验：不能把自己变成自己的子任务。
     */
    private fun moveTaskToParent(taskId: String, parentTaskId: String, core: NionCore): String {
        // 防止自引用
        if (taskId == parentTaskId) {
            return """{"error":"不能把任务变成自己的子任务"}"""
        }
        // 查询目标任务，确认存在且返回名称用于提示
        val parentTask = core.getTask(parentTaskId)
        core.updateTaskParent(taskId, parentTaskId)
        // 查询更新后的任务用于返回
        val task = core.getTask(taskId)
        return JSONObject().apply {
            put("success", true)
            put("task", taskToJson(task))
            put("message", "任务已成为「${parentTask.title}」的子任务")
        }.toString()
    }

    /**
     * 将子任务提升为独立主任务（parent_id 置空）。
     * Rust 层 updateTaskParent(id, None) 不会改变 category_id/group_id，
     * 所以提升后的任务仍留在原来的清单/分组中。
     */
    private fun promoteTaskToRoot(taskId: String, core: NionCore): String {
        core.updateTaskParent(taskId, null)
        // 查询更新后的任务用于返回
        val task = core.getTask(taskId)
        return JSONObject().apply {
            put("success", true)
            put("task", taskToJson(task))
            put("message", "任务已提升为独立主任务")
        }.toString()
    }
}

/**
 * 通用操作工具 —— 第 6 个工具，用于不归属于 CRUD 的其他操作。
 *
 * 通过 action 参数路由到不同操作，当前支持：
 * - set_recurrence：设置任务的每日循环规则和提醒时间
 * - remove_recurrence：移除任务的每日循环
 *
 * 未来可扩展更多操作类型（如设置提醒、批量操作等），不增加工具数量。
 *
 * Agent 场景示例：
 * - "每天 9 点提醒我" → action="set_recurrence", task_id="xxx", recurrence_rule="daily", reminder_time="09:00"
 * - "取消每天循环" → action="remove_recurrence", task_id="xxx"
 */
object ManageTool : Tool {
    override val name = "manage"
    override val description = "通用操作工具，用于不归属 CRUD 的其它操作。" +
        "通过 action 字段指定操作类型。" +
        "set_recurrence：设置任务每日循环（需 task_id、recurrence_rule、reminder_time）；" +
        "remove_recurrence：移除任务的每日循环（需 task_id）。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["set_recurrence", "remove_recurrence"],
                "description": "操作类型：set_recurrence=设置每日循环+提醒时间, remove_recurrence=移除每日循环"
            },
            "task_id": {
                "type": "string",
                "description": "目标任务 ID（所有 action 都需要）"
            },
            "recurrence_rule": {
                "type": "string",
                "enum": ["daily"],
                "description": "循环规则（仅 set_recurrence 需要）。传\"daily\"表示每天循环"
            },
            "reminder_time": {
                "type": "string",
                "description": "每日提醒时间，格式 HH:MM 如 09:00，精确到分钟（仅 set_recurrence 需要）"
            }
        },
        "required": ["action", "task_id"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val action = params.getString("action")
        val taskId = params.optString("task_id", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"manage 操作必须指定 task_id"}"""

        return when (action) {
            "set_recurrence" -> executeSetRecurrence(taskId, params, core)
            "remove_recurrence" -> executeRemoveRecurrence(taskId, core)
            else -> """{"error":"不支持的 action: $action"}"""
        }
    }

    /**
     * 设置任务的每日循环规则和提醒时间。
     * 调用 Rust 端 set_task_recurrence 方法。
     */
    private fun executeSetRecurrence(taskId: String, params: JSONObject, core: NionCore): String {
        val rule = params.optString("recurrence_rule", "").takeIf { it.isNotEmpty() }
        val time = params.optString("reminder_time", "").takeIf { it.isNotEmpty() }

        val task = core.setTaskRecurrence(taskId, rule, time)
        return JSONObject().apply {
            put("success", true)
            put("task", taskToJson(task))
            put("message", if (rule == "daily") "已设置每日循环${if (time != null) "，提醒时间 $time" else ""}" else "已清除循环设置")
        }.toString()
    }

    /**
     * 移除任务的每日循环。
     * 调用 Rust 端 remove_task_recurrence 方法。
     */
    private fun executeRemoveRecurrence(taskId: String, core: NionCore): String {
        val task = core.removeTaskRecurrence(taskId)
        return JSONObject().apply {
            put("success", true)
            put("task", taskToJson(task))
            put("message", "已移除每日循环")
        }.toString()
    }
}
