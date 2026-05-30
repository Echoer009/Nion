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
 * reminder 字段合并规则（反向）：
 * - 如果 recurrenceRule == "daily" 且 recurrenceReminderTime 非空 → 输出 recurrenceReminderTime（如 "08:00"）
 * - 否则 reminder 非空 → 输出 reminder（如 "2026-12-31T09:00"）
 * - 两者都有时优先输出 recurrenceReminderTime（日常提醒更有用）
 *
 * @param task UniFFI 生成的任务数据记录
 * @return 包含任务关键信息的 JSON 对象，null 字段已省略
 */
fun taskToJson(task: TaskData): JSONObject = JSONObject().apply {
    put("id", task.id)
    put("name", task.name)
    put("status", task.status)
    put("priority", task.priority)
    // description: 非空时才输出
    if (task.description != null) put("description", task.description)
    // reminder 合并输出：循环提醒时间优先于一次性提醒
    if (task.recurrenceRule == "daily" && task.recurrenceReminderTime != null) {
        put("reminder", task.recurrenceReminderTime)
    } else if (task.reminder != null) {
        put("reminder", task.reminder)
    }
    // 关联 ID: 非空时才输出
    if (task.parentId != null) put("parent_id", task.parentId)
    if (task.categoryId != null) put("category_id", task.categoryId)
    if (task.groupId != null) put("group_id", task.groupId)
    // focus_seconds: 仅非零时输出，0 是默认值
    if (task.focusSeconds > 0) put("focus_seconds", task.focusSeconds)
    // completed_at: 仅已完成时输出
    if (task.completedAt != null) put("completed_at", task.completedAt)
}

/**
 * 从统一的 reminder 参数解析出 Rust 层需要的三个字段。
 *
 * 解析规则：
 * - 包含 "T"（如 "2026-12-31T09:00"）→ 一次性提醒，设置 reminder
 * - 仅包含 ":"（如 "08:00"）→ 每日循环提醒，设置 recurrence_rule="daily" + recurrence_reminder_time
 * - 不匹配上述格式 → 按原始值作为一次性提醒处理
 *
 * @param reminder 统一的 reminder 参数值
 * @return Triple<recurrenceRule, recurrenceReminderTime, reminder> 对应 Rust 层的三个字段
 */
fun parseReminderParam(reminder: String): Triple<String?, String?, String?> {
    // 格式判断：包含 "T" 说明是日期时间格式（如 2026-12-31T09:00）→ 一次性提醒
    return if (reminder.contains("T")) {
        Triple(null, null, reminder)
    } else {
        // 不包含 "T"，视为每日循环提醒时间（如 "08:00"）
        Triple("daily", reminder, null)
    }
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
// 名称解析辅助函数
// 供 QueryTool / CreateTool 等工具共用的名称→ID 解析逻辑
// 匹配策略：精确匹配优先，包含匹配兜底，多匹配合并返回
// ═══════════════════════════════════════════════════════════════════

/**
 * 按名称模糊匹配清单。
 * 精确匹配优先，无精确匹配时用包含匹配，多匹配合并返回。
 */
private fun resolveChecklistsByName(name: String, core: NionCore): List<ChecklistData> {
    val all = core.getChecklists()
    val exact = all.filter { it.name == name }
    if (exact.isNotEmpty()) return exact
    return all.filter { it.name.contains(name) }
}

/**
 * 按名称模糊匹配分组，在指定清单范围内。
 * 精确匹配优先，无精确匹配时用包含匹配，多匹配合并返回。
 */
private fun resolveGroupsByName(name: String, checklistIds: List<String>, core: NionCore): List<GroupData> {
    val allGroups = checklistIds.flatMap { core.getGroupsByChecklist(it) }
    val exact = allGroups.filter { it.name == name }
    if (exact.isNotEmpty()) return exact
    return allGroups.filter { it.name.contains(name) }
}

/**
 * 按名称 + 可选范围解析唯一任务 ID。
 * 精确匹配优先，包含匹配兜底，多匹配时要求加 checklist/group 缩小范围。
 *
 * @param name          任务名称（精确或模糊匹配）
 * @param checklistName 可选，按清单名称缩小搜索范围
 * @param groupName     可选，按分组名称缩小搜索范围
 * @param core          NionCore 单例
 * @return 成功时 Pair<ID, "">，失败时 Pair<null, 错误JSON>
 */
private fun resolveTaskIdByName(
    name: String,
    checklistName: String?,
    groupName: String?,
    core: NionCore
): Pair<String?, String> {
    var tasks = core.getTasks()

    // 按 checklist 名称缩小范围
    if (checklistName != null) {
        val matchedChecklists = resolveChecklistsByName(checklistName, core)
        if (matchedChecklists.isEmpty()) {
            return null to """{"error":"未找到名称包含「$checklistName」的清单"}"""
        }
        val checklistIds = matchedChecklists.map { it.id }.toSet()
        tasks = tasks.filter { it.categoryId != null && it.categoryId in checklistIds }
    }

    // 按 group 名称缩小范围
    if (groupName != null) {
        val checklistIds = if (checklistName != null) {
            resolveChecklistsByName(checklistName, core).map { it.id }
        } else {
            core.getChecklists().map { it.id }
        }
        val matchedGroups = resolveGroupsByName(groupName, checklistIds, core)
        if (matchedGroups.isEmpty()) {
            return null to """{"error":"未找到名称包含「$groupName」的分组"}"""
        }
        val groupIds = matchedGroups.map { it.id }.toSet()
        tasks = tasks.filter { it.groupId != null && it.groupId in groupIds }
    }

    if (tasks.isEmpty()) {
        return null to """{"error":"在指定范围内未找到名称包含「$name」的任务"}"""
    }

    // 精确匹配优先
    val exact = tasks.filter { it.name == name }
    if (exact.size == 1) return exact[0].id to ""
    if (exact.size > 1) {
        val hint = buildScopeHint(checklistName, groupName)
        return null to """{"error":"找到多个名称为「$name」的任务$hint"}"""
    }

    // 包含匹配兜底
    val fuzzy = tasks.filter { it.name.contains(name) }
    if (fuzzy.isEmpty()) return null to """{"error":"未找到名称包含「$name」的任务"}"""
    if (fuzzy.size == 1) return fuzzy[0].id to ""
    val hint = buildScopeHint(checklistName, groupName)
    return null to """{"error":"找到多个名称包含「$name」的任务$hint"}"""
}

/**
 * 按名称 + 可选清单范围解析唯一分组 ID。
 * 精确匹配优先，包含匹配兜底，多匹配时要求加 checklist 缩小范围。
 *
 * @param name          分组名称
 * @param checklistName 可选，按清单名称缩小搜索范围
 * @param core          NionCore 单例
 * @return 成功时 Pair<ID, "">，失败时 Pair<null, 错误JSON>
 */
private fun resolveGroupIdByName(
    name: String,
    checklistName: String?,
    core: NionCore
): Pair<String?, String> {
    val checklistIds = if (checklistName != null) {
        val matched = resolveChecklistsByName(checklistName, core)
        if (matched.isEmpty()) {
            return null to """{"error":"未找到名称包含「$checklistName」的清单"}"""
        }
        matched.map { it.id }
    } else {
        core.getChecklists().map { it.id }
    }

    val allGroups = checklistIds.flatMap { core.getGroupsByChecklist(it) }
    if (allGroups.isEmpty()) {
        return null to """{"error":"在指定范围内未找到分组"}"""
    }

    // 精确匹配
    val exact = allGroups.filter { it.name == name }
    if (exact.size == 1) return exact[0].id to ""
    if (exact.size > 1) {
        val hint = if (checklistName == null) "，请加 checklist 参数缩小范围" else ""
        return null to """{"error":"找到多个名称为「$name」的分组$hint"}"""
    }

    // 包含匹配
    val fuzzy = allGroups.filter { it.name.contains(name) }
    if (fuzzy.isEmpty()) return null to """{"error":"未找到名称包含「$name」的分组"}"""
    if (fuzzy.size == 1) return fuzzy[0].id to ""
    val hint = if (checklistName == null) "，请加 checklist 参数缩小范围" else ""
    return null to """{"error":"找到多个名称包含「$name」的分组$hint"}"""
}

/**
 * 构建多匹配时的范围提示语。
 * 根据 checklist/group 参数是否已指定，给出不同的缩小范围建议。
 */
private fun buildScopeHint(checklistName: String?, groupName: String?): String {
    return when {
        checklistName == null && groupName == null -> "，请加 checklist 或 group 参数缩小范围"
        groupName == null -> "，请加 group 参数进一步缩小范围"
        else -> ""
    }
}

// ═══════════════════════════════════════════════════════════════════
// 统一工具实现
// ═══════════════════════════════════════════════════════════════════

/**
 * 统一查询工具 —— 名称驱动，LLM 无需知道 ID。
 *
 * 通过 entity_type 路由到不同的查询逻辑：
 * - task: 按 name 搜任务名 / 按 checklist+group 名称筛选 / 无参数返回全部
 * - checklist: 按 name 筛选 / 无参数返回全部
 * - group: 按 checklist 名称查某清单下的分组
 * - weather: 天气查询
 *
 * 所有名称参数均支持模糊匹配，精确匹配优先，包含匹配兜底，
 * 多匹配合并返回并在结果中标注 matched_checklists / matched_groups。
 *
 * Agent 场景示例：
 * - "我有哪些任务？" → entity_type="task"
 * - "学习清单下有什么？" → entity_type="task", checklist="学习"
 * - "学习数学" → entity_type="task", name="学习数学"
 * - "有哪些清单？" → entity_type="checklist"
 * - "学习清单的分组" → entity_type="group", checklist="学习"
 */
object QueryTool : Tool {
    override val name = "query"
    override val affectsData = emptySet<DataType>()
    override val description = "查询数据。entity_type: task/checklist/group/weather。可用 checklist/group 按名称筛选，name 按名称搜索。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group", "weather"]
            },
            "checklist": { "type": "string" },
            "group": { "type": "string" },
            "name": { "type": "string" },
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
            "checklist" -> executeChecklistQuery(params, core)
            "group" -> executeGroupQuery(params, core)
            "weather" -> executeWeatherQuery(params, core)
            else -> """{"error":"不支持的 entity_type: $entityType"}"""
        }
    }

    /**
     * 任务查询路由。
     * 优先级：name 按名字搜 > checklist+group > checklist > group > 全部
     */
    private fun executeTaskQuery(params: JSONObject, core: NionCore): String {
        val nameParam = params.optString("name", "").takeIf { it.isNotEmpty() }
        val checklistParam = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val groupParam = params.optString("group", "").takeIf { it.isNotEmpty() }

        // 按 name 搜索任务：从全部任务中按名称模糊匹配
        if (nameParam != null && checklistParam == null && groupParam == null) {
            val allTasks = core.getTasks()
            // 精确匹配优先
            val exact = allTasks.filter { it.name == nameParam }
            val matched = if (exact.isNotEmpty()) exact else allTasks.filter { it.name.contains(nameParam) }
            return JSONObject().apply {
                put("tasks", taskListToJson(matched))
                put("count", matched.size)
            }.toString()
        }

        // 按清单名称 + 分组名称筛选
        if (checklistParam != null) {
            val matchedChecklists = resolveChecklistsByName(checklistParam, core)
            if (matchedChecklists.isEmpty()) {
                return """{"error":"未找到名称包含「$checklistParam」的清单"}"""
            }
            val checklistIds = matchedChecklists.map { it.id }

            // 有 group 参数时进一步按分组名筛选
            if (groupParam != null) {
                val matchedGroups = resolveGroupsByName(groupParam, checklistIds, core)
                if (matchedGroups.isEmpty()) {
                    return """{"error":"在匹配的清单中未找到名称包含「$groupParam」的分组"}"""
                }
                // 每个匹配的分组查询其下任务，合并结果
                val tasks = matchedGroups.flatMap { group ->
                    core.getTasksByCategory(group.checklistId, group.id)
                }
                return JSONObject().apply {
                    put("tasks", taskListToJson(tasks))
                    put("count", tasks.size)
                    // 多匹配时标注匹配了哪些清单和分组
                    if (matchedChecklists.size > 1) {
                        put("matched_checklists", JSONArray(matchedChecklists.map { it.name }))
                    }
                    if (matchedGroups.size > 1) {
                        put("matched_groups", JSONArray(matchedGroups.map { it.name }))
                    }
                }.toString()
            }

            // 只按清单筛选，不限定分组
            val tasks = checklistIds.flatMap { cid ->
                core.getTasksByCategory(cid, null)
            }
            return JSONObject().apply {
                put("tasks", taskListToJson(tasks))
                put("count", tasks.size)
                if (matchedChecklists.size > 1) {
                    put("matched_checklists", JSONArray(matchedChecklists.map { it.name }))
                }
            }.toString()
        }

        // 只按分组名称筛选，无 checklist：全局搜分组名匹配 → 查询任务
        if (groupParam != null) {
            val allChecklists = core.getChecklists()
            val matchedGroups = resolveGroupsByName(groupParam, allChecklists.map { it.id }, core)
            if (matchedGroups.isEmpty()) {
                return """{"error":"未找到名称包含「$groupParam」的分组"}"""
            }
            val tasks = matchedGroups.flatMap { group ->
                core.getTasksByCategory(group.checklistId, group.id)
            }
            return JSONObject().apply {
                put("tasks", taskListToJson(tasks))
                put("count", tasks.size)
                if (matchedGroups.size > 1) {
                    put("matched_groups", JSONArray(matchedGroups.map { it.name }))
                }
            }.toString()
        }

        // 无筛选参数：返回全部任务
        val tasks = core.getTasks()
        return JSONObject().apply {
            put("tasks", taskListToJson(tasks))
            put("count", tasks.size)
        }.toString()
    }

    /**
     * 清单查询。
     * 有 name 参数时按名称模糊筛选，无参数时返回全部。
     */
    private fun executeChecklistQuery(params: JSONObject, core: NionCore): String {
        val nameParam = params.optString("name", "").takeIf { it.isNotEmpty() }
        val allChecklists = core.getChecklists()

        val matched = if (nameParam != null) {
            // 精确匹配优先
            val exact = allChecklists.filter { it.name == nameParam }
            if (exact.isNotEmpty()) exact else allChecklists.filter { it.name.contains(nameParam) }
        } else {
            allChecklists
        }

        return JSONObject().apply {
            put("checklists", checklistListToJson(matched))
            put("count", matched.size)
        }.toString()
    }

    /**
     * 分组查询 —— 按 checklist 名称找到清单，再返回其下所有分组。
     * 多个清单匹配时合并所有分组结果。
     */
    private fun executeGroupQuery(params: JSONObject, core: NionCore): String {
        val checklistParam = params.optString("checklist", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"查询分组时必须指定 checklist 参数"}"""

        val matchedChecklists = resolveChecklistsByName(checklistParam, core)
        if (matchedChecklists.isEmpty()) {
            return """{"error":"未找到名称包含「$checklistParam」的清单"}"""
        }

        // 合并所有匹配清单下的分组
        val groups = matchedChecklists.flatMap { core.getGroupsByChecklist(it.id) }
        return JSONObject().apply {
            put("groups", groupListToJson(groups))
            put("count", groups.size)
            if (matchedChecklists.size > 1) {
                put("matched_checklists", JSONArray(matchedChecklists.map { it.name }))
            }
        }.toString()
    }

    /**
     * 天气查询 —— 查看用户所在位置的当前天气或未来预报。
     *
     * 从 NionApp 获取 Context，调用 WeatherService 获取数据，
     * 根据 type 参数返回格式化的天气文本给 LLM。
     *
     * @param params 包含 type "current" 或 "forecast" 的参数
     * @param core   NionCore 单例，用于读取位置缓存和天气缓存
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
     * - 天气描述，含白天/夜间标识
     * - 实际温度 + 体感温度
     * - 湿度、风速、降水量
     * - 云量、能见度
     * - 空气质量，PM2.5、AQI
     * - 今日温度范围
     */
    private fun formatCurrentWeather(data: FullWeatherData): String {
        val current = data.current
        val desc = weatherDescription(current.weatherCode)
        val dayNight = if (current.isDay) "" else "，夜间"

        val todayRange = if (data.daily.days.isNotEmpty()) {
            val today = data.daily.days[0]
            "\n今日温度范围：${"%.0f".format(today.tempMin)}°C ~ ${"%.0f".format(today.tempMax)}°C"
        } else ""

        return buildString {
            append("当前天气：$desc$dayNight\n")
            append("温度：${"%.1f".format(current.temperature)}°C")
            // 体感温度与实际温度差异超过 1°C 时显示
            if (kotlin.math.abs(current.apparentTemperature - current.temperature) > 1.0) {
                append("，体感 ${"%.1f".format(current.apparentTemperature)}°C")
            }
            append("\n")
            append("湿度：${current.humidity}%\n")
            append("风速：${"%.1f".format(current.windSpeed)} km/h\n")
            append("降水量：${"%.1f".format(current.precipitation)} mm\n")
            append("云量：${current.cloudCover}%\n")
            // 能见度低于 5km 时特别标注，大雾/霾预警
            val visKm = current.visibility / 1000.0
            if (visKm < 5.0) {
                append("能见度：${"%.1f".format(visKm)} km ⚠ 能见度较低\n")
            } else {
                append("能见度：${"%.1f".format(visKm)} km\n")
            }

            // 空气质量信息
            data.airQuality?.let { aq ->
                append("空气质量：AQI ${aq.aqi}，${aqiDescription(aq.aqi)}，PM2.5 ${"%.0f".format(aq.pm25)} μg/m³\n")
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
 * 统一创建工具 —— 名称驱动，所有归属关系用名称指定。
 *
 * 支持批量创建：传 items 数组可一次创建多个实体。
 * 外层 checklist/group/parent 作为批量默认值，item 未指定时自动继承。
 *
 * 归属解析规则：
 * - checklist: 按名称匹配清单，未找到则报错
 * - group: 先在匹配到的 checklist 下找分组，未找到则自动创建该分组
 * - parent: 按名称匹配父任务，必须唯一匹配，多匹配时报错
 *
 * Agent 场景示例：
 * - "帮我建一个任务" → entity_type="task", name="买菜"
 * - "在学习清单下建任务" → entity_type="task", name="考研计划", checklist="学习"
 * - "在学习清单语文分组下建任务" → entity_type="task", name="背诵", checklist="学习", group="语文"
 * - "在学习数学下面建子任务" → entity_type="task", name="第一章", parent="学习数学"
 * - "建三个任务" → entity_type="task", items=[{"name":"买菜"},{"name":"跑步"},{"name":"读书"}]
 * - "建一个学习清单" → entity_type="checklist", name="学习"
 * - "在学习清单下建一个分组" → entity_type="group", name="数学", checklist="学习"
 */
object CreateTool : Tool {
    override val name = "create"
    override val affectsData = setOf(DataType.TASK_DATA)
    override val description = "创建实体，支持批量。checklist/group/parent 用名称指定归属。当任务与时间相关时，必须根据任务类型填写 reminder 参数：每日重复任务用 HH:MM 格式如 08:00，一次性任务用 YYYY-MM-DDTHH:MM 格式如 2026-06-15T09:00。不要只写标题不设时间。"

    /**
     * 参数 Schema。
     * reminder 字段已合并：传 "HH:MM" 表示每日循环提醒，传 "YYYY-MM-DDTHH:MM" 表示一次性提醒。
     */
    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group"]
            },
            "name": { "type": "string" },
            "description": { "type": "string" },
            "priority": {
                "type": "string",
                "enum": ["low", "medium", "high"]
            },
            "checklist": { "type": "string" },
            "group": { "type": "string" },
            "parent": { "type": "string" },
            "reminder": {
                "type": "string",
                "description": "提醒时间。\"HH:MM\"格式为每日循环提醒，\"YYYY-MM-DDTHH:MM\"格式为一次性提醒"
            },
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

        // 批量创建模式
        if (itemsArray != null && itemsArray.length() > 0) {
            return executeBatchCreate(entityType, itemsArray, params, core)
        }

        return when (entityType) {
            "task" -> executeCreateTask(params, core)
            "checklist" -> executeCreateChecklist(params, core)
            "group" -> executeCreateGroup(params, core)
            else -> """{"error":"不支持的 entity_type: $entityType"}"""
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 名称解析辅助函数
    // ═══════════════════════════════════════════════════════════════

    /**
     * 按名称匹配清单，返回 ID。
     * 未找到时返回 null，调用方负责报错。
     */
    private fun resolveChecklistId(name: String, core: NionCore): String? {
        val matched = resolveChecklistsByName(name, core)
        return matched.firstOrNull()?.id
    }

    /**
     * 按名称匹配父任务，要求唯一匹配。
     * 精确匹配优先，包含匹配兜底。
     * 多匹配时返回 null，调用方负责报错。
     */
    private fun resolveParentId(name: String, core: NionCore): String? {
        val allTasks = core.getTasks()
        // 精确匹配
        val exact = allTasks.filter { it.name == name }
        if (exact.size == 1) return exact[0].id
        if (exact.size > 1) return null
        // 包含匹配
        val fuzzy = allTasks.filter { it.name.contains(name) }
        return if (fuzzy.size == 1) fuzzy[0].id else null
    }

    /**
     * 在指定清单下按名称查找分组，未找到则自动创建。
     *
     * @param groupName   分组名称
     * @param checklistId 清单 ID
     * @param core        NionCore 单例
     * @return 分组 ID
     */
    private fun resolveOrCreateGroupId(groupName: String, checklistId: String, core: NionCore): String {
        val groups = core.getGroupsByChecklist(checklistId)
        // 精确匹配
        val exact = groups.find { it.name == groupName }
        if (exact != null) return exact.id
        // 包含匹配
        val fuzzy = groups.find { it.name.contains(groupName) }
        if (fuzzy != null) return fuzzy.id
        // 未找到，自动创建分组
        val newGroup = core.createGroup(groupName, checklistId, null)
        return newGroup.id
    }

    /**
     * 解析任务创建所需的归属 ID。
     * 从 checklist/group/parent 名称参数解析出对应的 ID。
     *
     * @return 成功时返回 Triple<categoryId, groupId, parentId>，失败时返回错误 JSON 字符串
     */
    private fun resolveTaskOwnership(
        checklistName: String?, groupName: String?, parentName: String?, core: NionCore
    ): kotlin.Pair<Triple<String?, String?, String?>?, String> {
        // 解析 checklist
        var categoryId: String? = null
        if (checklistName != null) {
            categoryId = resolveChecklistId(checklistName, core)
                ?: return null to """{"error":"未找到名称包含「$checklistName」的清单"}"""
        }

        // 解析 group
        var groupId: String? = null
        if (groupName != null) {
            if (categoryId == null) {
                // 没指定 checklist，尝试全局找分组
                val allChecklists = core.getChecklists()
                val allGroups = allChecklists.flatMap { core.getGroupsByChecklist(it.id) }
                val exact = allGroups.filter { it.name == groupName }
                val matched = if (exact.isNotEmpty()) exact else allGroups.filter { it.name.contains(groupName) }
                if (matched.isEmpty()) {
                    return null to """{"error":"未找到名称包含「$groupName」的分组，请先指定 checklist 或手动创建分组"}"""
                }
                if (matched.size > 1) {
                    return null to """{"error":"找到多个名称包含「$groupName」的分组，请同时指定 checklist 缩小范围"}"""
                }
                groupId = matched[0].id
                // 自动继承分组所属的清单
                categoryId = matched[0].checklistId
            } else {
                // 在指定 checklist 下找分组，未找到则自动创建
                groupId = resolveOrCreateGroupId(groupName, categoryId, core)
            }
        }

        // 解析 parent
        var parentId: String? = null
        if (parentName != null) {
            parentId = resolveParentId(parentName, core)
                ?: return null to """{"error":"未找到唯一匹配「$parentName」的任务，请用更精确的名称指定父任务"}"""
        }

        return Triple(categoryId, groupId, parentId) to ""
    }

    // ═══════════════════════════════════════════════════════════════
    // 单个创建
    // ═══════════════════════════════════════════════════════════════

    /**
     * 创建任务。
     * checklist/group/parent 参数按名称解析为 ID。
     * reminder 参数按格式自动判断：含"T"为一次性提醒，否则为每日循环提醒。
     */
    private fun executeCreateTask(params: JSONObject, core: NionCore): String {
        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"创建任务时必须指定 name"}"""

        val checklistName = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val groupName = params.optString("group", "").takeIf { it.isNotEmpty() }
        val parentName = params.optString("parent", "").takeIf { it.isNotEmpty() }

        val (ownership, errorMsg) = resolveTaskOwnership(checklistName, groupName, parentName, core)
        if (ownership == null) return errorMsg

        val (categoryId, groupId, parentId) = ownership

        // 解析统一的 reminder 参数，拆分为 Rust 层的三个字段
        val reminderRaw = params.optString("reminder", "").takeIf { it.isNotEmpty() }
        val (recurrenceRule, recurrenceReminderTime, reminder) = if (reminderRaw != null) {
            parseReminderParam(reminderRaw)
        } else {
            Triple(null, null, null)
        }

        val task = core.createTask(
            name = name,
            description = params.optString("description", "").takeIf { it.isNotEmpty() },
            priority = params.optString("priority", "medium"),
            categoryId = categoryId,
            parentId = parentId,
            groupId = groupId,
            recurrenceRule = recurrenceRule,
            recurrenceReminderTime = recurrenceReminderTime,
        )
        // 如果是一次性提醒，需要二次更新，createTask 不支持 reminder 参数
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

    /**
     * 创建分组。
     * checklist 参数按名称解析为清单 ID。
     */
    private fun executeCreateGroup(params: JSONObject, core: NionCore): String {
        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"创建分组时必须指定 name"}"""
        val checklistName = params.optString("checklist", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"创建分组时必须指定 checklist"}"""

        val checklistId = resolveChecklistId(checklistName, core)
            ?: return """{"error":"未找到名称包含「$checklistName」的清单"}"""

        val color = params.optString("color", "").takeIf { it.isNotEmpty() }
        val group = core.createGroup(name, checklistId, color)
        return JSONObject().apply {
            put("success", true)
            put("group", groupToJson(group))
        }.toString()
    }

    // ═══════════════════════════════════════════════════════════════
    // 批量创建
    // ═══════════════════════════════════════════════════════════════

    /**
     * 批量创建实体。
     * 外层 checklist/group/parent 作为默认值，item 未指定时自动继承。
     * 部分失败时返回成功和失败各自的计数及错误详情，不会中断整个批次。
     */
    private fun executeBatchCreate(entityType: String, items: org.json.JSONArray, params: JSONObject, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0

        // 外层参数作为批量默认值
        val outerChecklist = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val outerGroup = params.optString("group", "").takeIf { it.isNotEmpty() }
        val outerParent = params.optString("parent", "").takeIf { it.isNotEmpty() }

        for (i in 0 until items.length()) {
            val itemParams = items.getJSONObject(i)
            try {
                when (entityType) {
                    "task" -> {
                        val name = itemParams.optString("name", "").takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("批量创建任务时每项必须包含 name，第 ${i + 1} 项缺失")
                        // item 有值用 item 的，没有则 fallback 到外层参数
                        val checklistName = itemParams.optString("checklist", "").takeIf { it.isNotEmpty() } ?: outerChecklist
                        val groupName = itemParams.optString("group", "").takeIf { it.isNotEmpty() } ?: outerGroup
                        val parentName = itemParams.optString("parent", "").takeIf { it.isNotEmpty() } ?: outerParent

                        val (ownership, errorMsg) = resolveTaskOwnership(checklistName, groupName, parentName, core)
                        if (ownership == null) throw IllegalArgumentException(errorMsg)

                        val (categoryId, groupId, parentId) = ownership
                        // 解析统一的 reminder 参数
                        val reminderRaw = itemParams.optString("reminder", "").takeIf { it.isNotEmpty() }
                        val (recurrenceRule, recurrenceReminderTime, reminder) = if (reminderRaw != null) {
                            parseReminderParam(reminderRaw)
                        } else {
                            Triple(null, null, null)
                        }
                        val task = core.createTask(
                            name = name,
                            description = itemParams.optString("description", "").takeIf { it.isNotEmpty() },
                            priority = itemParams.optString("priority", "medium"),
                            categoryId = categoryId,
                            parentId = parentId,
                            groupId = groupId,
                            recurrenceRule = recurrenceRule,
                            recurrenceReminderTime = recurrenceReminderTime,
                        )
                        // 批量创建也支持一次性提醒，需二次更新
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
                        val checklistName = itemParams.optString("checklist", "").takeIf { it.isNotEmpty() } ?: outerChecklist
                            ?: throw IllegalArgumentException("批量创建分组时每项必须包含 checklist，第 ${i + 1} 项缺失")
                        val checklistId = resolveChecklistId(checklistName, core)
                            ?: throw IllegalArgumentException("未找到名称包含「$checklistName」的清单")
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
}

/**
 * 统一更新工具 —— 名称驱动，同时支持字段修改和归属移动。
 *
 * 用 name 按名称定位实体，用 new_name 改名，用 checklist/group/parent 变更归属。
 * 支持批量更新：传 names 数组可一次更新多个同类型实体。
 *
 * 归属变更规则：
 * - checklist：将任务移到目标清单（清除旧分组），或将分组移到目标清单
 * - group：将任务移到目标分组（自动继承分组的清单归属）
 * - parent：将任务挂到目标父任务下成为子任务，传空字符串提升为独立主任务
 * - group 优先级高于 checklist（group 隐含了所属清单）
 *
 * Agent 场景示例：
 * - "把任务标为完成" → entity_type="task", name="学习数学", status="done"
 * - "改一下提醒时间" → entity_type="task", name="学习数学", reminder="2026-12-31T09:00"
 * - "把任务移到工作清单" → entity_type="task", name="学习数学", checklist="工作"
 * - "把任务移到语文分组" → entity_type="task", name="学习数学", group="语文"
 * - "变成子任务" → entity_type="task", name="第一章", parent="学习数学"
 * - "提升为主任务" → entity_type="task", name="第一章", parent=""
 * - "清单改名" → entity_type="checklist", name="学习", new_name="学习计划"
 * - "分组移到工作清单" → entity_type="group", name="语文", checklist="工作"
 * - "分组改颜色" → entity_type="group", name="语文", color="#4CAF50"
 */
object UpdateTool : Tool {
    override val name = "update"
    override val affectsData = setOf(DataType.TASK_DATA)
    override val description = "更新实体。优先用id/ids精确定位，回退name/names。checklist/group/parent变更归属。支持批量。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group"]
            },
            "id": { "type": "string", "description": "按 ID 定位单个实体，跳过名称解析" },
            "ids": {
                "type": "array",
                "items": { "type": "string" },
                "description": "按 ID 数组批量更新，跳过名称解析"
            },
            "name": { "type": "string", "description": "按名称定位，精确优先包含兜底" },
            "names": {
                "type": "array",
                "items": { "type": "string" },
                "description": "按名称数组批量更新"
            },
            "new_name": { "type": "string", "description": "新名称，用于改名" },
            "description": { "type": "string", "description": "任务描述" },
            "priority": {
                "type": "string",
                "enum": ["low", "medium", "high"],
                "description": "优先级"
            },
            "status": {
                "type": "string",
                "enum": ["todo", "in_progress", "done"],
                "description": "状态"
            },
            "checklist": { "type": "string", "description": "将任务移到目标清单，按名称匹配，不是范围筛选" },
            "group": { "type": "string", "description": "将任务移到目标分组，按名称匹配，自动继承分组的清单" },
            "parent": { "type": "string", "description": "挂到目标父任务下，按名称匹配，传空字符串清除父任务" },
            "reminder": {
                "type": "string",
                "description": "提醒时间，HH:MM每日循环，YYYY-MM-DDTHH:MM一次性"
            },
            "color": { "type": "string", "description": "分组颜色如#FF5722，仅entity_type=group有效" }
        },
        "required": ["entity_type"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")

        // ── ID 路径：优先级最高，跳过所有名称解析 ──
        val idsArray = params.optJSONArray("ids")
        if (idsArray != null && idsArray.length() > 0) {
            return executeBatchUpdateByIds(entityType, idsArray, params, core)
        }

        val singleId = params.optString("id", "").takeIf { it.isNotEmpty() }
        if (singleId != null) {
            return when (entityType) {
                "task" -> executeUpdateTask("", params, core, resolvedId = singleId)
                "checklist" -> executeUpdateChecklist("", params, core, resolvedId = singleId)
                "group" -> executeUpdateGroup("", params, core, resolvedId = singleId)
                else -> """{"error":"不支持的 entity_type: $entityType"}"""
            }
        }

        // ── 名称路径：原有逻辑 ──
        val namesArray = params.optJSONArray("names")
        if (namesArray != null && namesArray.length() > 0) {
            return executeBatchUpdate(entityType, namesArray, params, core)
        }

        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"更新操作必须指定 id、ids、name 或 names"}"""

        return when (entityType) {
            "task" -> executeUpdateTask(name, params, core)
            "checklist" -> executeUpdateChecklist(name, params, core)
            "group" -> executeUpdateGroup(name, params, core)
            else -> """{"error":"不支持的 entity_type: $entityType"}"""
        }
    }

    /**
     * 批量更新实体。
     * 遍历 names 数组，逐个解析名称为 ID，应用相同的变更字段和归属变更。
     * 部分失败时返回各自的 success/fail，不中断整个批次。
     */
    private fun executeBatchUpdate(entityType: String, names: org.json.JSONArray, params: JSONObject, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0

        for (i in 0 until names.length()) {
            val name = names.getString(i)
            try {
                when (entityType) {
                    "task" -> {
                        val result = executeUpdateTask(name, params, core)
                        val json = JSONObject(result)
                        if (!json.optBoolean("success", false)) throw IllegalArgumentException(json.optString("error", "未知错误"))
                        results.put(JSONObject().apply {
                            put("name", name)
                            put("success", true)
                            put("task", json.optJSONObject("task"))
                        })
                        successCount++
                    }
                    "checklist" -> {
                        val matched = resolveChecklistsByName(name, core)
                        if (matched.isEmpty()) throw IllegalArgumentException("未找到名称包含「$name」的清单")
                        val newName = params.optString("new_name", "").takeIf { it.isNotEmpty() }
                            ?: throw IllegalArgumentException("批量更新清单时必须指定 new_name")
                        val checklist = core.updateChecklistName(matched.first().id, newName)
                        results.put(JSONObject().apply {
                            put("name", name)
                            put("success", true)
                            put("checklist", checklistToJson(checklist))
                        })
                        successCount++
                    }
                    "group" -> {
                        val result = executeUpdateGroup(name, params, core)
                        val json = JSONObject(result)
                        if (!json.optBoolean("success", false)) throw IllegalArgumentException(json.optString("error", "未知错误"))
                        results.put(JSONObject().apply {
                            put("name", name)
                            put("success", true)
                            put("group", json.optJSONObject("group"))
                        })
                        successCount++
                    }
                    else -> throw IllegalArgumentException("不支持的批量更新类型: $entityType")
                }
            } catch (e: Exception) {
                results.put(JSONObject().apply {
                    put("name", name)
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

    /**
     * 按 ID 数组批量更新实体。
     * 跳过名称解析，逐个用 ID 调用对应的更新方法，应用相同的变更字段。
     * 部分失败时返回各自的 success/fail，不中断整个批次。
     */
    private fun executeBatchUpdateByIds(entityType: String, ids: org.json.JSONArray, params: JSONObject, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0

        for (i in 0 until ids.length()) {
            val id = ids.getString(i)
            try {
                val result: String = when (entityType) {
                    "task" -> executeUpdateTask("", params, core, resolvedId = id)
                    "checklist" -> executeUpdateChecklist("", params, core, resolvedId = id)
                    "group" -> executeUpdateGroup("", params, core, resolvedId = id)
                    else -> throw IllegalArgumentException("不支持的批量更新类型: $entityType")
                }
                val json = JSONObject(result)
                if (!json.optBoolean("success", false)) throw IllegalArgumentException(json.optString("error", "未知错误"))
                // 从返回结果中提取实体对象
                val entityKey = when (entityType) {
                    "task" -> "task"
                    "checklist" -> "checklist"
                    "group" -> "group"
                    else -> null
                }
                results.put(JSONObject().apply {
                    put("id", id)
                    put("success", true)
                    if (entityKey != null) put(entityKey, json.optJSONObject(entityKey))
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

    /**
     * 更新任务 —— 同时支持字段修改和归属移动。
     *
     * 定位方式：
     * - resolvedId 非空时直接使用 ID，跳过名称解析
     * - 否则用 name 全局解析任务名称（不做范围限定，重名时报错）
     *
     * checklist/group/parent 参数用于变更归属：
     * - checklist：移到目标清单，同时清除旧分组
     * - group：移到目标分组，自动继承分组的清单（group 优先级 > checklist）
     * - parent：挂到目标父任务下；传空字符串清除父任务（提升为根任务）
     * - 同时指定 group + checklist 时，group 的清单优先
     *
     * @param name        任务名称（resolvedId 为空时用于名称解析）
     * @param params      参数对象
     * @param core        NionCore 单例
     * @param resolvedId  预解析的任务 ID，非空时跳过名称解析
     */
    private fun executeUpdateTask(name: String, params: JSONObject, core: NionCore, resolvedId: String? = null): String {
        // ID 定位：resolvedId 非空时直接使用；否则走名称解析
        val id: String
        if (resolvedId != null) {
            id = resolvedId
        } else {
            val (resolved, err) = resolveTaskIdByName(name, null, null, core)
            if (resolved == null) return err
            id = resolved
        }

        // ── 归属变更参数 ──
        val moveChecklist = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val moveGroup = params.optString("group", "").takeIf { it.isNotEmpty() }
        val moveParent = params.optString("parent", "").takeIf { it.isNotEmpty() }
        // parent="" 表示清除父任务（提升为根任务）
        val clearParent = params.has("parent") && params.optString("parent", "").isEmpty()

        // ── 解析归属变更目标 ──
        var newCategoryId: String? = null
        var newGroupId: String? = null
        var needClearGroup = false

        if (moveGroup != null) {
            // 移到分组：解析分组名 → 同时获得 groupId 和继承的 checklistId
            val allChecklists = core.getChecklists()
            val allGroups = allChecklists.flatMap { core.getGroupsByChecklist(it.id) }
            val exact = allGroups.filter { it.name == moveGroup }
            val matched = if (exact.isNotEmpty()) exact else allGroups.filter { it.name.contains(moveGroup) }
            if (matched.isEmpty()) return """{"error":"未找到名称包含「$moveGroup」的分组"}"""
            if (matched.size > 1) return """{"error":"找到多个名称包含「$moveGroup」的分组，请同时指定 checklist"}"""
            newGroupId = matched[0].id
            // group 优先：自动继承分组的清单归属
            newCategoryId = matched[0].checklistId
        } else if (moveChecklist != null) {
            // 移到清单：解析清单名 → 设置新 checklistId，清除旧分组
            val matchedChecklists = resolveChecklistsByName(moveChecklist, core)
            if (matchedChecklists.isEmpty()) return """{"error":"未找到名称包含「$moveChecklist」的清单"}"""
            if (matchedChecklists.size > 1) return """{"error":"找到多个名称包含「$moveChecklist」的清单"}"""
            newCategoryId = matchedChecklists[0].id
            needClearGroup = true
        }

        // ── 处理父任务变更 ──
        if (moveParent != null) {
            val (parentId, parentErr) = resolveTaskIdByName(moveParent, null, null, core)
            if (parentId == null) return parentErr
            if (id == parentId) return """{"error":"不能把任务变成自己的子任务"}"""
            core.updateTaskParent(id, parentId)
        } else if (clearParent) {
            core.updateTaskParent(id, null)
        }

        // ── 清除旧分组（移到新清单时） ──
        if (needClearGroup) {
            core.updateTaskGroup(id, null)
        }

        // ── 解析 reminder 参数 ──
        val reminderRaw = params.optString("reminder", "").takeIf { it.isNotEmpty() }
        val (recurrenceRule, recurrenceReminderTime, reminder) = if (reminderRaw != null) {
            parseReminderParam(reminderRaw)
        } else {
            Triple(null, null, null)
        }

        // ── 执行更新 ──
        val task = core.updateTask(
            id = id,
            name = params.optString("new_name", "").takeIf { it.isNotEmpty() },
            description = params.optString("description", "").takeIf { it.isNotEmpty() },
            priority = params.optString("priority", "").takeIf { it.isNotEmpty() },
            status = params.optString("status", "").takeIf { it.isNotEmpty() },
            categoryId = newCategoryId,
            reminder = reminder,
            groupId = newGroupId,
            recurrenceRule = recurrenceRule,
            recurrenceReminderTime = recurrenceReminderTime,
        )
        return JSONObject().apply {
            put("success", true)
            put("task", taskToJson(task))
        }.toString()
    }

    /**
     * 更新清单名称。
     *
     * @param name        清单名称（resolvedId 为空时用于名称解析）
     * @param params      参数对象
     * @param core        NionCore 单例
     * @param resolvedId  预解析的清单 ID，非空时跳过名称解析
     */
    private fun executeUpdateChecklist(name: String, params: JSONObject, core: NionCore, resolvedId: String? = null): String {
        val newName = params.optString("new_name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"更新清单时必须指定 new_name"}"""

        val id: String
        if (resolvedId != null) {
            id = resolvedId
        } else {
            val matched = resolveChecklistsByName(name, core)
            if (matched.isEmpty()) {
                return """{"error":"未找到名称包含「$name」的清单"}"""
            }
            if (matched.size > 1) {
                return """{"error":"找到多个名称包含「$name」的清单，请用更精确的名称"}"""
            }
            id = matched[0].id
        }

        val checklist = core.updateChecklistName(id, newName)
        return JSONObject().apply {
            put("success", true)
            put("checklist", checklistToJson(checklist))
        }.toString()
    }

    /**
     * 更新分组 —— 支持改名、改颜色、移动到其他清单。
     *
     * @param name        分组名称（resolvedId 为空时用于名称解析）
     * @param params      参数对象
     * @param core        NionCore 单例
     * @param resolvedId  预解析的分组 ID，非空时跳过名称解析
     */
    private fun executeUpdateGroup(name: String, params: JSONObject, core: NionCore, resolvedId: String? = null): String {
        val newName = params.optString("new_name", "").takeIf { it.isNotEmpty() }
        val moveChecklist = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val color = params.optString("color", "").takeIf { it.isNotEmpty() }

        // ID 定位：resolvedId 非空时直接使用；否则走名称解析
        val id: String
        if (resolvedId != null) {
            id = resolvedId
        } else {
            val (resolved, err) = resolveGroupIdByName(name, null, core)
            if (resolved == null) return err
            id = resolved
        }

        // 归属变更：移到目标清单
        if (moveChecklist != null) {
            val matchedChecklists = resolveChecklistsByName(moveChecklist, core)
            if (matchedChecklists.isEmpty()) return """{"error":"未找到名称包含「$moveChecklist」的清单"}"""
            if (matchedChecklists.size > 1) return """{"error":"找到多个名称包含「$moveChecklist」的清单"}"""
            // 移动分组，Rust 层会同步更新组内任务的 checklist
            val group = core.moveGroupToChecklist(id, matchedChecklists[0].id)
            // 如果还有其他更新（改名/改色），二次更新
            if (newName != null || color != null) {
                val updated = core.updateGroup(group.id, newName ?: group.name, color)
                return JSONObject().apply {
                    put("success", true)
                    put("group", groupToJson(updated))
                }.toString()
            }
            return JSONObject().apply {
                put("success", true)
                put("group", groupToJson(group))
            }.toString()
        }

        // 没有归属变更，只更新名称/颜色
        if (newName == null && color == null) {
            return """{"error":"更新分组时必须至少指定 new_name、checklist 或 color 之一"}"""
        }
        val group = core.updateGroup(id, newName ?: core.getGroup(id).name, color)
        return JSONObject().apply {
            put("success", true)
            put("group", groupToJson(group))
        }.toString()
    }
}

/**
 * 统一删除工具 —— 支持 ID 直接定位和名称模糊定位。
 *
 * 优先使用 ID 定位（精确、无歧义），ID 不可用时回退到名称 + 范围限定。
 * 支持批量删除：传 ids 或 names 数组可一次删除多个同类型实体。
 * 不可撤销，删除前 AI 应确认。
 *
 * 执行优先级：ids → id → names → name
 *
 * Agent 场景示例：
 * - "删掉学习数学这个任务" → entity_type="task", name="学习数学"
 * - "删掉这三个任务" → entity_type="task", ids=["id1","id2","id3"]
 * - "删除学习清单" → entity_type="checklist", id="清单ID"
 * - "删掉语文分组" → entity_type="group", name="语文", checklist="学习"
 */
object DeleteTool : Tool {
    override val name = "delete"
    override val affectsData = setOf(DataType.TASK_DATA)
    override val description = "删除实体。优先用id/ids精确定位，回退name/names+checklist/group。支持批量。不可撤销。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group"]
            },
            "id": { "type": "string", "description": "按 ID 定位单个实体，跳过名称解析" },
            "ids": {
                "type": "array",
                "items": { "type": "string" },
                "description": "按 ID 数组批量删除，跳过名称解析"
            },
            "name": { "type": "string", "description": "按名称定位，精确优先包含兜底" },
            "names": {
                "type": "array",
                "items": { "type": "string" },
                "description": "按名称数组批量删除"
            },
            "checklist": { "type": "string", "description": "按清单名称限定范围，用于定位task和group" },
            "group": { "type": "string", "description": "按分组名称限定范围，用于定位task" }
        },
        "required": ["entity_type"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val entityType = params.getString("entity_type")

        // ── ID 路径：优先级最高，跳过所有名称解析 ──
        val idsArray = params.optJSONArray("ids")
        if (idsArray != null && idsArray.length() > 0) {
            return executeBatchDeleteByIds(entityType, idsArray, core)
        }

        val singleId = params.optString("id", "").takeIf { it.isNotEmpty() }
        if (singleId != null) {
            return executeSingleDeleteById(entityType, singleId, core)
        }

        // ── 名称路径：原有逻辑 ──
        val namesArray = params.optJSONArray("names")
        if (namesArray != null && namesArray.length() > 0) {
            return executeBatchDelete(entityType, namesArray, params, core)
        }

        val name = params.optString("name", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"删除操作必须指定 id、ids、name 或 names"}"""

        return executeSingleDelete(entityType, name, params, core)
    }

    /**
     * 按 ID 直接删除单个实体。
     * 跳过名称解析，直接调用 Rust 删除方法。
     * 返回结果中包含 ID，供闹钟取消等后续处理使用。
     */
    private fun executeSingleDeleteById(entityType: String, id: String, core: NionCore): String {
        val deleted = when (entityType) {
            "task" -> core.deleteTask(id)
            "checklist" -> core.deleteChecklist(id)
            "group" -> core.deleteGroup(id)
            else -> return """{"error":"不支持的 entity_type: $entityType"}"""
        }

        return JSONObject().apply {
            put("success", true)
            put("deleted", deleted)
            put("id", id)
        }.toString()
    }

    /**
     * 按 ID 数组批量删除实体。
     * 跳过名称解析，逐个调用 Rust 删除方法。
     * 部分失败时不中断整个批次。
     */
    private fun executeBatchDeleteByIds(entityType: String, ids: org.json.JSONArray, core: NionCore): String {
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

    /**
     * 单个删除实体（名称路径）。
     * 根据实体类型 + 名称解析出 ID，调用 Rust 删除方法。
     * 返回结果中包含解析后的 ID，供闹钟取消等后续处理使用。
     */
    private fun executeSingleDelete(entityType: String, name: String, params: JSONObject, core: NionCore): String {
        val (id, err) = resolveEntityId(entityType, name, params, core)
        if (id == null) return err

        val deleted = when (entityType) {
            "task" -> core.deleteTask(id)
            "checklist" -> core.deleteChecklist(id)
            "group" -> core.deleteGroup(id)
            else -> return """{"error":"不支持的 entity_type: $entityType"}"""
        }

        return JSONObject().apply {
            put("success", true)
            put("deleted", deleted)
            // 返回解析后的 ID，供 extractTaskId 提取以取消闹钟
            put("id", id)
        }.toString()
    }

    /**
     * 批量删除实体（名称路径）。
     * 遍历 names 数组，逐个解析名称为 ID 并删除，收集结果。
     * 部分失败时不中断整个批次。
     */
    private fun executeBatchDelete(entityType: String, names: org.json.JSONArray, params: JSONObject, core: NionCore): String {
        val results = org.json.JSONArray()
        var successCount = 0
        var failCount = 0
        // 外层 checklist/group 作为批量默认范围限定
        val scopeChecklist = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val scopeGroup = params.optString("group", "").takeIf { it.isNotEmpty() }

        for (i in 0 until names.length()) {
            val name = names.getString(i)
            try {
                val (id, err) = resolveEntityId(entityType, name, scopeChecklist, scopeGroup, core)
                if (id == null) throw IllegalArgumentException(err)
                when (entityType) {
                    "task" -> core.deleteTask(id)
                    "checklist" -> core.deleteChecklist(id)
                    "group" -> core.deleteGroup(id)
                    else -> throw IllegalArgumentException("不支持的批量删除类型: $entityType")
                }
                results.put(JSONObject().apply {
                    put("name", name)
                    put("success", true)
                })
                successCount++
            } catch (e: Exception) {
                results.put(JSONObject().apply {
                    put("name", name)
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

    /**
     * 通用实体名称 → ID 解析。
     * 根据 entity_type 路由到对应的名称解析函数。
     *
     * @param entityType    实体类型
     * @param name          实体名称
     * @param params        参数对象，从中提取 checklist/group 范围限定
     * @param core          NionCore 单例
     * @return 成功时 Pair<ID, "">，失败时 Pair<null, 错误JSON>
     */
    private fun resolveEntityId(
        entityType: String, name: String, params: JSONObject, core: NionCore
    ): Pair<String?, String> {
        val checklistName = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val groupName = params.optString("group", "").takeIf { it.isNotEmpty() }
        return resolveEntityId(entityType, name, checklistName, groupName, core)
    }

    /**
     * 通用实体名称 → ID 解析（重载，接受显式范围参数）。
     * 供批量操作复用，避免重复解析 params。
     */
    private fun resolveEntityId(
        entityType: String, name: String, checklistName: String?, groupName: String?, core: NionCore
    ): Pair<String?, String> {
        return when (entityType) {
            "task" -> resolveTaskIdByName(name, checklistName, groupName, core)
            "checklist" -> {
                val matched = resolveChecklistsByName(name, core)
                when {
                    matched.isEmpty() -> null to """{"error":"未找到名称包含「$name」的清单"}"""
                    matched.size > 1 -> null to """{"error":"找到多个名称包含「$name」的清单，请用更精确的名称"}"""
                    else -> matched[0].id to ""
                }
            }
            "group" -> resolveGroupIdByName(name, checklistName, core)
            else -> null to """{"error":"不支持的 entity_type: $entityType"}"""
        }
    }
}

/**
 * 排序工具 —— 支持 ID 直接排序和名称解析排序。
 *
 * 优先使用 ordered_ids（精确、无歧义），ID 不可用时回退到 ordered_names + 范围限定。
 * 支持任务、清单、分组三种实体类型。
 *
 * Agent 场景示例：
 * - "按优先级排一下任务" → action="reorder", entity_type="task", ordered_ids=["id1","id2","id3"]
 * - "调整清单顺序" → action="reorder", entity_type="checklist", ordered_names=["工作","学习","生活"]
 * - "分组排序" → action="reorder", entity_type="group", ordered_names=["语文","数学","英语"], checklist="学习"
 */
object ManageTool : Tool {
    override val name = "manage"
    override val affectsData = setOf(DataType.TASK_DATA)
    override val description = "排序操作。优先用ordered_ids精确定位，回退ordered_names。支持task/checklist/group。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["reorder"]
            },
            "entity_type": {
                "type": "string",
                "enum": ["task", "checklist", "group"]
            },
            "ordered_ids": {
                "type": "array",
                "items": { "type": "string" },
                "description": "按 ID 数组直接排序，跳过名称解析"
            },
            "ordered_names": {
                "type": "array",
                "items": { "type": "string" },
                "description": "按名称数组排序，精确优先包含兜底"
            },
            "checklist": { "type": "string", "description": "按清单名称限定范围，用于解析task和group" },
            "group": { "type": "string", "description": "按分组名称限定范围，用于解析task" }
        },
        "required": ["action"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val action = params.getString("action")

        return when (action) {
            "reorder" -> executeReorder(params, core)
            else -> """{"error":"不支持的 action: $action"}"""
        }
    }

    /**
     * 自由排序实体。
     *
     * 优先使用 ordered_ids（跳过名称解析），回退到 ordered_names（逐个解析为 ID）。
     * Rust 层按数组索引设置 sort_order。
     *
     * @param params 参数对象
     * @param core   NionCore 单例
     * @return 排序结果的 JSON 字符串
     */
    private fun executeReorder(params: JSONObject, core: NionCore): String {
        // 校验 entity_type
        val entityType = params.optString("entity_type", "").takeIf { it.isNotEmpty() }
            ?: return """{"error":"reorder 操作必须指定 entity_type（task/checklist/group）"}"""
        if (entityType !in listOf("task", "checklist", "group")) {
            return """{"error":"不支持的 entity_type: $entityType，可选值：task, checklist, group"}"""
        }

        // ── ID 路径：优先使用 ordered_ids ──
        val orderedIdsArray = params.optJSONArray("ordered_ids")
        if (orderedIdsArray != null && orderedIdsArray.length() > 0) {
            val orderedIds = (0 until orderedIdsArray.length()).map { orderedIdsArray.getString(it) }
            return executeReorderByIds(entityType, orderedIds, core)
        }

        // ── 名称路径：ordered_names ──
        val orderedNamesArray = params.optJSONArray("ordered_names")
            ?: return """{"error":"reorder 操作必须指定 ordered_ids 或 ordered_names"}"""
        if (orderedNamesArray.length() == 0) {
            return """{"error":"ordered_names 不能为空，至少需要一个名称"}"""
        }

        // 范围限定参数
        val checklistScope = params.optString("checklist", "").takeIf { it.isNotEmpty() }
        val groupScope = params.optString("group", "").takeIf { it.isNotEmpty() }

        // 将名称数组解析为 ID 数组
        val orderedIds = mutableListOf<String>()
        for (i in 0 until orderedNamesArray.length()) {
            val name = orderedNamesArray.getString(i)
            val (id, err) = when (entityType) {
                "task" -> resolveTaskIdByName(name, checklistScope, groupScope, core)
                "checklist" -> {
                    val matched = resolveChecklistsByName(name, core)
                    when {
                        matched.isEmpty() -> null to """{"error":"排序失败：未找到名称包含「$name」的清单"}"""
                        matched.size > 1 -> null to """{"error":"排序失败：找到多个名称包含「$name」的清单"}"""
                        else -> matched[0].id to ""
                    }
                }
                "group" -> resolveGroupIdByName(name, checklistScope, core)
                else -> null to """{"error":"不支持的排序类型: $entityType"}"""
            }
            if (id == null) return err
            orderedIds.add(id)
        }

        return executeReorderByIds(entityType, orderedIds, core)
    }

    /**
     * 按 ID 数组调用 Rust 排序方法。
     * 供 ordered_ids 直接路径和 ordered_names 解析后的路径共用。
     */
    private fun executeReorderByIds(entityType: String, orderedIds: List<String>, core: NionCore): String {
        try {
            when (entityType) {
                "task" -> core.reorderTasks(orderedIds)
                "checklist" -> core.reorderChecklists(orderedIds)
                "group" -> core.reorderGroups(orderedIds)
            }
        } catch (e: Exception) {
            return """{"error":"排序失败：${e.message ?: "未知错误"}"}"""
        }

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
            put("message", "已按指定顺序排列 ${orderedIds.size} 个${typeLabel}")
        }.toString()
    }
}
