package com.echonion.nion.ui.companion.tools

import com.echonion.nion.NionApp
import com.echonion.nion.ui.companion.weather.FullWeatherData
import com.echonion.nion.ui.companion.weather.WeatherService
import com.echonion.nion.ui.companion.weather.aqiDescription
import com.echonion.nion.ui.companion.weather.weatherDescription
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
 * 只输出非空字段，跳过 null 值和默认值以减少 token 消耗。
 *
 * @param task UniFFI 生成的任务数据记录
 * @return 包含任务关键信息的 JSON 对象（null 字段已省略）
 */
fun taskToJson(task: TaskData): JSONObject = JSONObject().apply {
    put("id", task.id)
    put("title", task.title)
    put("status", task.status)
    put("priority", task.priority)
    // description: 非空时才输出
    if (task.description != null) put("description", task.description)
    // reminder: 非空时才输出
    if (task.reminder != null) put("reminder", task.reminder)
    // 关联 ID: 非空时才输出
    if (task.parentId != null) put("parent_id", task.parentId)
    if (task.categoryId != null) put("category_id", task.categoryId)
    if (task.groupId != null) put("group_id", task.groupId)
    // focus_seconds: 仅非零时输出（0 是默认值）
    if (task.focusSeconds > 0) put("focus_seconds", task.focusSeconds)
    // 循环相关: 非空时才输出
    if (task.recurrenceRule != null) put("recurrence_rule", task.recurrenceRule)
    if (task.recurrenceReminderTime != null) put("recurrence_reminder_time", task.recurrenceReminderTime)
    // completed_at: 仅已完成时输出
    if (task.completedAt != null) put("completed_at", task.completedAt)
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
    if (group.color != null) put("color", group.color)
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
    override val affectsData = emptySet<DataType>()
    override val description = "查询数据。entity_type: task/checklist/group/weather。" +
        "task 可按 id/category_id/parent_id 筛选；group 需 checklist_id；weather 需定位权限，type=current|forecast。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group", "weather"]
            },
            "id": { "type": "string" },
            "category_id": { "type": "string" },
            "checklist_id": { "type": "string" },
            "parent_id": { "type": "string" },
            "type": {
                "type": "string",
                "enum": ["current", "forecast"]
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
            "weather" -> executeWeatherQuery(params, core)
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

    /**
     * 天气查询 —— 查看用户所在位置的当前天气或未来预报。
     *
     * 从 NionApp 获取 Context，调用 WeatherService 获取数据，
     * 根据 type 参数返回格式化的天气文本给 LLM。
     *
     * @param params 包含 type（"current" 或 "forecast"）的参数
     * @param core   NionCore 单例（用于读取位置缓存和天气缓存）
     * @return 格式化的天气信息文本
     */
    private suspend fun executeWeatherQuery(params: JSONObject, core: NionCore): String {
        val type = params.optString("type", "current")

        val app = NionApp.instance
            ?: return """{"error":"无法获取应用上下文"}"""

        val weather = WeatherService.fetchWeather(app, core)
            ?: return """{"error":"获取天气数据失败，可能是定位不可用或网络问题。请告诉用户天气功能暂时不可用，建议检查定位权限和网络连接。"}"""

        return when (type) {
            "current" -> formatCurrentWeather(weather)
            "forecast" -> formatForecast(weather)
            else -> """{"error":"不支持的天气查询类型: $type，可选：current, forecast"}"""
        }
    }

    /**
     * 格式化当前天气实况为 LLM 可理解的文本。
     *
     * 输出格式包含：
     * - 天气描述（含白天/夜间标识）
     * - 实际温度 + 体感温度
     * - 湿度、风速、降水量
     * - 云量、能见度
     * - 空气质量（PM2.5、AQI）
     * - 今日温度范围
     */
    private fun formatCurrentWeather(data: FullWeatherData): String {
        val current = data.current
        val desc = weatherDescription(current.weatherCode)
        val dayNight = if (current.isDay) "" else "（夜间）"

        val todayRange = if (data.daily.days.isNotEmpty()) {
            val today = data.daily.days[0]
            "\n今日温度范围：${"%.0f".format(today.tempMin)}°C ~ ${"%.0f".format(today.tempMax)}°C"
        } else ""

        return buildString {
            append("当前天气：$desc$dayNight\n")
            append("温度：${"%.1f".format(current.temperature)}°C")
            // 体感温度与实际温度差异超过 1°C 时显示
            if (kotlin.math.abs(current.apparentTemperature - current.temperature) > 1.0) {
                append("（体感 ${"%.1f".format(current.apparentTemperature)}°C）")
            }
            append("\n")
            append("湿度：${current.humidity}%\n")
            append("风速：${"%.1f".format(current.windSpeed)} km/h\n")
            append("降水量：${"%.1f".format(current.precipitation)} mm\n")
            append("云量：${current.cloudCover}%\n")
            // 能见度低于 5km 时特别标注（大雾/霾预警）
            val visKm = current.visibility / 1000.0
            if (visKm < 5.0) {
                append("能见度：${"%.1f".format(visKm)} km ⚠ 能见度较低\n")
            } else {
                append("能见度：${"%.1f".format(visKm)} km\n")
            }

            // 空气质量信息
            data.airQuality?.let { aq ->
                append("空气质量：AQI ${aq.aqi}（${aqiDescription(aq.aqi)}），PM2.5 ${"%.0f".format(aq.pm25)} μg/m³\n")
            }

            append(todayRange)
        }
    }

    /**
     * 格式化天气预报为 LLM 可理解的文本。
     *
     * 包含两部分：
     * 1. 未来 24 小时逐小时预报（温度、降水概率、天气、风速、UV）
     * 2. 未来 7 天逐日预报（最高/最低温、总降水、最大风速、UV峰值）
     */
    private fun formatForecast(data: FullWeatherData): String {
        val sb = StringBuilder()

        sb.appendLine("=== 未来 24 小时逐小时预报 ===")
        for (h in data.hourly.hours) {
            val timeLabel = h.hour.substring(11)
            val desc = weatherDescription(h.weatherCode)
            val precipMark = if (h.precipitationProb >= 50) " ⚠" else ""
            sb.appendLine(
                "$timeLabel | $desc | ${"%.0f".format(h.temperature)}°C | 降水${h.precipitationProb}% | 风速${"%.0f".format(h.windSpeed)}km/h | UV${"%.0f".format(h.uvIndex)}$precipMark"
            )
        }

        sb.appendLine()

        sb.appendLine("=== 未来 7 天逐日预报 ===")
        for (d in data.daily.days) {
            val dateLabel = d.date.substring(5)
            sb.appendLine(
                "$dateLabel | ${"%.0f".format(d.tempMin)}°C ~ ${"%.0f".format(d.tempMax)}°C | 降水${d.precipitationProbabilityMax}% ${"%.1f".format(d.precipitationSum)}mm | 最大风速${"%.0f".format(d.windSpeedMax)}km/h | UV峰值${"%.0f".format(d.uvIndexMax)}"
            )
        }

        return sb.toString()
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
    override val affectsData = setOf(DataType.TASK_DATA)
    override val description = "创建实体，支持批量(items数组)。entity_type: task需title, checklist需name, group需name+checklist_id。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group"]
            },
            "title": { "type": "string" },
            "description": { "type": "string" },
            "priority": {
                "type": "string",
                "enum": ["low", "medium", "high"]
            },
            "category_id": { "type": "string" },
            "parent_id": { "type": "string" },
            "group_id": { "type": "string" },
            "recurrence_rule": {
                "type": "string",
                "enum": ["daily"]
            },
            "recurrence_reminder_time": { "type": "string" },
            "reminder": { "type": "string" },
            "name": { "type": "string" },
            "checklist_id": { "type": "string" },
            "color": { "type": "string" },
            "items": {
                "type": "array",
                "items": { "type": "object" }
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
            return executeBatchCreate(entityType, itemsArray, params, core)
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
    private fun executeBatchCreate(entityType: String, items: org.json.JSONArray, params: JSONObject, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0

        // 外层参数作为批量默认值：item 未指定时自动继承
        // 用法示例：{ "entity_type":"task", "parent_id":"abc", "items":[{"title":"子1"},{"title":"子2"}] }
        // 子1、子2 会自动继承 parent_id="abc"，无需每项重复指定
        val outerCategoryId = params.optString("category_id", "").takeIf { it.isNotEmpty() }
        val outerParentId = params.optString("parent_id", "").takeIf { it.isNotEmpty() }
        val outerGroupId = params.optString("group_id", "").takeIf { it.isNotEmpty() }

        for (i in 0 until items.length()) {
            val itemParams = items.getJSONObject(i)
            try {
                when (entityType) {
                    "task" -> {
                        val title = itemParams.optString("title", "").takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("批量创建任务时每项必须包含 title，第 ${i + 1} 项缺失")
                        // item 有值用 item 的，没有则 fallback 到外层参数
                        val categoryId = itemParams.optString("category_id", "").takeIf { it.isNotEmpty() } ?: outerCategoryId
                        val parentId = itemParams.optString("parent_id", "").takeIf { it.isNotEmpty() } ?: outerParentId
                        val groupId = itemParams.optString("group_id", "").takeIf { it.isNotEmpty() } ?: outerGroupId
                        val task = core.createTask(
                            title = title,
                            description = itemParams.optString("description", "").takeIf { it.isNotEmpty() },
                            priority = itemParams.optString("priority", "medium"),
                            categoryId = categoryId,
                            parentId = parentId,
                            groupId = groupId,
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
    override val affectsData = setOf(DataType.TASK_DATA)
    override val description = "更新实体，支持批量(ids数组)。只传需修改的字段。task可改title/status/priority/reminder等；checklist改name；group改name/color。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group"]
            },
            "id": { "type": "string" },
            "ids": {
                "type": "array",
                "items": { "type": "string" }
            },
            "title": { "type": "string" },
            "description": { "type": "string" },
            "priority": {
                "type": "string",
                "enum": ["low", "medium", "high"]
            },
            "status": {
                "type": "string",
                "enum": ["todo", "in_progress", "done"]
            },
            "category_id": { "type": "string" },
            "reminder": { "type": "string" },
            "group_id": { "type": "string" },
            "recurrence_rule": {
                "type": "string",
                "enum": ["daily"]
            },
            "recurrence_reminder_time": { "type": "string" },
            "name": { "type": "string" },
            "color": { "type": "string" }
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
    override val affectsData = setOf(DataType.TASK_DATA)
    override val description = "删除实体，支持批量(ids数组)，不可撤销。删清单时任务保留(变未分类)，删分组时任务保留(变未分组)。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group"]
            },
            "id": { "type": "string" },
            "ids": {
                "type": "array",
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
 * 通用操作工具 —— 负责结构性操作：移动和排序。
 *
 * 通过 action 参数路由到不同操作，当前支持：
 * - move：将实体从一个容器移动到另一个，保留所有数据（如专注时长），支持批量
 * - reorder：自由排序任务/清单/分组，AI 按期望顺序传入 ordered_ids 即可
 *
 * move 操作的子类型（通过 entity_type + target_type 组合路由）：
 * - task → checklist：将任务移到另一个清单（自动清除旧分组归属）
 * - task → group：将任务移到另一个分组（自动继承分组的清单归属）
 * - task → task：将任务移到另一个任务下成为子任务
 * - task → root：将子任务提升为独立主任务
 * - group → checklist：将整个分组移到另一个清单（组内任务跟随移动）
 *
 * Agent 场景示例：
 * - "把这个任务移到工作清单" → action="move", entity_type="task", id="xxx", target_type="checklist", target_id="xxx"
 * - "把这三个任务都移到语文分组" → action="move", entity_type="task", ids=["id1","id2","id3"], target_type="group", target_id="xxx"
 * - "把数学分组移到工作清单" → action="move", entity_type="group", id="xxx", target_type="checklist", target_id="xxx"
 * - "把这个任务变成子任务" → action="move", entity_type="task", id="xxx", target_type="task", target_id="parent_id"
 * - "把这个子任务提升为主任务" → action="move", entity_type="task", id="xxx", target_type="root"
 * - "按优先级排一下任务" → action="reorder", entity_type="task", ordered_ids=["高优id","中优id","低优id"]
 * - "调整清单顺序" → action="reorder", entity_type="checklist", ordered_ids=["id3","id1","id2"]
 * - "分组排序" → action="reorder", entity_type="group", ordered_ids=["id2","id1"]
 */
object ManageTool : Tool {
    override val name = "manage"
    override val affectsData = setOf(DataType.TASK_DATA)
    override val description = "结构性操作。action=move: 移动实体(task→checklist/group/task/root, group→checklist)，支持批量(ids)。" +
        "action=reorder: 按 ordered_ids 排列顺序，支持 task/checklist/group。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["move", "reorder"]
            },
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group"]
            },
            "id": { "type": "string" },
            "ids": {
                "type": "array",
                "items": { "type": "string" }
            },
            "target_type": {
                "type": "string",
                "enum": ["checklist", "group", "task", "root"]
            },
            "target_id": { "type": "string" },
            "ordered_ids": {
                "type": "array",
                "items": { "type": "string" }
            }
        },
        "required": ["action"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val action = params.getString("action")

        return when (action) {
            "move" -> executeMove(params, core)
            "reorder" -> executeReorder(params, core)
            else -> """{"error":"不支持的 action: $action"}"""
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // move 操作
    // ═══════════════════════════════════════════════════════════════

    /**
     * 移动操作路由。
     * 解析 entity_type、target_type、id/ids，路由到对应的移动方法。
     * 支持批量移动（ids 数组）和单个移动（id）。
     */
    private fun executeMove(params: JSONObject, core: NionCore): String {
        val entityType = params.optString("entity_type", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"move 操作必须指定 entity_type（task/group）"}"""
        val targetType = params.optString("target_type", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"move 操作必须指定 target_type（checklist/group/task/root）"}"""
        // root 类型不需要 target_id
        val targetId = params.optString("target_id", "").takeIf { it.isNotEmpty() }
            ?: if (targetType != "root") return """{"error":"move 操作必须指定 target_id（root 类型除外）"}""" else ""

        val singleId = params.optString("id", "").takeIf { it.isNotEmpty() }
        val idsArray = params.optJSONArray("ids")

        // 批量移动
        if (idsArray != null && idsArray.length() > 0) {
            return executeBatchMove(entityType, idsArray, targetType, targetId, core)
        }

        // 单个移动
        if (singleId == null) return """{"error":"move 操作必须指定 id 或 ids"}"""

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
    private fun executeBatchMove(entityType: String, ids: JSONArray, targetType: String, targetId: String, core: NionCore): String {
        val results = JSONArray()
        var successCount = 0
        var failCount = 0

        for (i in 0 until ids.length()) {
            val id = ids.getString(i)
            try {
                when {
                    entityType == "task" && targetType == "checklist" -> {
                        moveTaskToChecklist(id, targetId, core)
                        results.put(JSONObject().apply { put("id", id); put("success", true) })
                        successCount++
                    }
                    entityType == "task" && targetType == "group" -> {
                        moveTaskToGroup(id, targetId, core)
                        results.put(JSONObject().apply { put("id", id); put("success", true) })
                        successCount++
                    }
                    entityType == "task" && targetType == "task" -> {
                        moveTaskToParent(id, targetId, core)
                        results.put(JSONObject().apply { put("id", id); put("success", true) })
                        successCount++
                    }
                    entityType == "task" && targetType == "root" -> {
                        promoteTaskToRoot(id, core)
                        results.put(JSONObject().apply { put("id", id); put("success", true) })
                        successCount++
                    }
                    entityType == "group" && targetType == "checklist" -> {
                        moveGroupToChecklist(id, targetId, core)
                        results.put(JSONObject().apply { put("id", id); put("success", true) })
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

    // ═══════════════════════════════════════════════════════════════
    // reorder 操作
    // ═══════════════════════════════════════════════════════════════

    /**
     * 自由排序实体。
     * AI 按期望顺序传入 ordered_ids，Rust 层按数组索引设置 sort_order。
     * 支持任务、清单、分组三种实体类型。
     * 不做范围限定 —— AI 通过 query 工具拿到列表后自行决定排序，完全自由。
     */
    private fun executeReorder(params: JSONObject, core: NionCore): String {
        // 校验 entity_type
        val entityType = params.optString("entity_type", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"reorder 操作必须指定 entity_type（task/checklist/group）"}"""
        if (entityType !in listOf("task", "checklist", "group")) {
            return """{"error":"不支持的 entity_type: $entityType，可选值：task, checklist, group"}"""
        }

        // 校验 ordered_ids
        val orderedIdsArray = params.optJSONArray("ordered_ids")
            ?: return """{"error":"reorder 操作必须指定 ordered_ids 数组"}"""
        if (orderedIdsArray.length() == 0) {
            return """{"error":"ordered_ids 不能为空，至少需要一个 ID"}"""
        }

        // 将 JSONArray 转为 List<String>
        val orderedIds = (0 until orderedIdsArray.length()).map { orderedIdsArray.getString(it) }

        // 根据实体类型调用对应的 Rust 排序方法
        try {
            when (entityType) {
                "task" -> core.reorderTasks(orderedIds)
                "checklist" -> core.reorderChecklists(orderedIds)
                "group" -> core.reorderGroups(orderedIds)
            }
        } catch (e: Exception) {
            return """{"error":"排序失败：${e.message ?: "未知错误"}"}"""
        }

        // 构建实体类型的中文名用于提示
        val typeLabel = when (entityType) {
            "task" -> "任务"
            "checklist" -> "清单"
            "group" -> "分组"
            else -> entityType
        }

        return JSONObject().apply {
            put("success", true)
            put("entity_type", entityType)
            put("ordered_count", orderedIds.size)
            put("message", "已按指定顺序排列 $orderedIds.size 个${typeLabel}")
        }.toString()
    }
}
