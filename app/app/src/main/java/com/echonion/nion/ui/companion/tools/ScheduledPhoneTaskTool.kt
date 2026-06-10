package com.echonion.nion.ui.companion.tools

import android.util.Log
import com.echonion.nion.NionApp
import com.echonion.nion.reminder.PhoneAutomationScheduler
import org.json.JSONArray
import org.json.JSONObject
import uniffi.nion_core.NionCore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 定时手机任务工具 —— 让主 AI 能创建和管理定时 Phone Agent 自动化任务。
 *
 * 支持两种调度类型：
 * - once：一次性任务，在指定日期和时间执行一次后自动禁用
 * - daily：每日循环任务，每天在指定时间执行，持续有效
 *
 * 数据存储：使用 settings 表的 `phone_automation_tasks` key，值为 JSON 数组。
 * 每个数组元素是一个 PhoneAutomationTask 对象。
 *
 * 操作类型（通过 action 参数指定）：
 * - create：创建新的定时任务
 * - list：列出所有定时任务（可按 enabled 筛选）
 * - update：更新现有任务（名称、描述、时间、启用状态等）
 * - delete：删除指定任务
 *
 * 创建/更新/删除操作完成后会自动触发 PhoneAutomationScheduler.rescheduleAll()，
 * 确保闹钟状态与任务列表一致。
 */
object ScheduledPhoneTaskTool : Tool {

    private const val TAG = "ScheduledPhoneTaskTool"

    /** settings 表中存储定时任务列表的 key */
    const val SETTING_KEY = "phone_automation_tasks"

    override val name = "schedule_phone_task"

    override val description = """
管理定时手机自动化任务。可以让 Phone Agent 在指定时间自动执行手机操作。
action=create: 创建新任务，需提供 name/task_description/schedule_type/schedule_time，once类型还需 schedule_date。
action=list: 列出所有任务。
action=update: 修改任务，需提供 id 和要修改的字段。
action=delete: 删除任务，需提供 id。
适用场景：用户说"每天早上8点帮我给xx发微信"、"明天下午3点提醒我去xx"等需要定时自动操作手机的场景。
    """.trimIndent()

    /** 定时任务不影响 Nion 内部数据 */
    override val affectsData: Set<DataType> = emptySet()

    override fun parametersSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("enum", org.json.JSONArray().apply {
                        put("create"); put("list"); put("update"); put("delete")
                    })
                    put("description", "操作类型：create创建/list列表/update更新/delete删除")
                })
                put("name", JSONObject().apply {
                    put("type", "string")
                    put("description", "任务名称，如'每天给妈妈发早安'")
                })
                put("task_description", JSONObject().apply {
                    put("type", "string")
                    put("description", "Phone Agent 任务描述，如'打开微信给妈妈发消息说早安'")
                })
                put("schedule_type", JSONObject().apply {
                    put("type", "string")
                    put("enum", org.json.JSONArray().apply {
                        put("once"); put("daily")
                    })
                    put("description", "调度类型：once一次性/daily每日循环")
                })
                put("schedule_time", JSONObject().apply {
                    put("type", "string")
                    put("description", "执行时间，格式 HH:MM，如 '08:00'")
                })
                put("schedule_date", JSONObject().apply {
                    put("type", "string")
                    put("description", "一次性任务的执行日期，格式 YYYY-MM-DD，仅 schedule_type=once 时必填")
                })
                put("id", JSONObject().apply {
                    put("type", "string")
                    put("description", "任务 ID，update/delete 操作必填")
                })
                put("enabled", JSONObject().apply {
                    put("type", "boolean")
                    put("description", "是否启用（update 操作可用，暂停/恢复任务）")
                })
            })
            put("required", org.json.JSONArray().apply {
                put("action")
            })
        }
    }

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val action = params.optString("action", "")
        Log.d(TAG, "执行操作: action=$action")

        return when (action) {
            "create" -> executeCreate(params, core)
            "list" -> executeList(params, core)
            "update" -> executeUpdate(params, core)
            "delete" -> executeDelete(params, core)
            else -> """{"error":"未知操作: $action，支持 create/list/update/delete"}"""
        }
    }

    /**
     * 创建新的定时手机任务。
     *
     * 必填参数：name, task_description, schedule_type, schedule_time
     * once 类型还需 schedule_date。
     * 创建后自动触发调度器重注册所有闹钟。
     */
    private fun executeCreate(params: JSONObject, core: NionCore): String {
        val name = params.optString("name", "").trim()
        val taskDescription = params.optString("task_description", "").trim()
        val scheduleType = params.optString("schedule_type", "").trim()
        val scheduleTime = params.optString("schedule_time", "").trim()
        val scheduleDate = params.opt("schedule_date")?.toString()?.trim()

        // 参数校验
        if (name.isEmpty()) return """{"error":"name 不能为空"}"""
        if (taskDescription.isEmpty()) return """{"error":"task_description 不能为空，需要描述 Phone Agent 要执行的具体操作"}"""
        if (scheduleType !in listOf("once", "daily")) return """{"error":"schedule_type 必须是 once 或 daily"}"""
        if (!scheduleTime.matches(Regex("\\d{2}:\\d{2}"))) return """{"error":"schedule_time 格式错误，应为 HH:MM（如 08:00）"}"""
        if (scheduleType == "once" && (scheduleDate == null || !scheduleDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))) {
            return """{"error":"once 类型任务必须提供 schedule_date（格式 YYYY-MM-DD）"}"""
        }

        // 验证时间合理性
        val timeParts = scheduleTime.split(":")
        val hour = timeParts[0].toIntOrNull() ?: return """{"error":"小时格式错误"}"""
        val minute = timeParts[1].toIntOrNull() ?: return """{"error":"分钟格式错误"}"""
        if (hour !in 0..23 || minute !in 0..59) return """{"error":"时间超出范围（小时 0-23，分钟 0-59）"}"""

        // 构建 JSON 对象
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val taskJson = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("name", name)
            put("task_description", taskDescription)
            put("schedule_type", scheduleType)
            put("schedule_time", scheduleTime)
            put("schedule_date", if (scheduleType == "once") scheduleDate else JSONObject.NULL)
            put("enabled", true)
            put("last_run_at", JSONObject.NULL)
            put("last_result", JSONObject.NULL)
            put("created_at", now)
        }

        // 追加到已有列表
        val tasks = loadTasks(core)
        tasks.put(taskJson)
        saveTasks(core, tasks)

        // 触发调度器重注册
        triggerReschedule()

        val id = taskJson.getString("id")
        Log.d(TAG, "创建定时任务成功: id=$id, name=$name, type=$scheduleType, time=$scheduleTime")
        return JSONObject().apply {
            put("success", true)
            put("message", "定时任务已创建: $name")
            put("id", id)
            put("task", taskJson)
        }.toString()
    }

    /**
     * 列出所有定时手机任务。
     * 可通过 enabled 参数筛选：true 仅启用的，false 仅禁用的，不传则列出全部。
     */
    private fun executeList(params: JSONObject, core: NionCore): String {
        val tasks = loadTasks(core)
        val enabledFilter = if (params.has("enabled")) params.optBoolean("enabled") else null

        val result = JSONArray()
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            if (enabledFilter != null && task.optBoolean("enabled") != enabledFilter) continue
            result.put(task)
        }

        val count = result.length()
        val activeCount = (0 until tasks.length()).count { tasks.getJSONObject(it).optBoolean("enabled") }
        Log.d(TAG, "列出定时任务: 共${tasks.length()}个，其中${activeCount}个启用")

        return JSONObject().apply {
            put("success", true)
            put("total", count)
            put("active", activeCount)
            put("tasks", result)
        }.toString()
    }

    /**
     * 更新已有的定时手机任务。
     * 必须提供 id 参数，其余字段可选（只更新提供的字段）。
     */
    private fun executeUpdate(params: JSONObject, core: NionCore): String {
        val id = params.optString("id", "").trim()
        if (id.isEmpty()) return """{"error":"update 操作必须提供 id"}"""

        val tasks = loadTasks(core)
        var found = false
        var updatedTask: JSONObject? = null

        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            if (task.getString("id") == id) {
                found = true
                // 逐字段更新（只更新提供的字段）
                if (params.has("name")) task.put("name", params.getString("name").trim())
                if (params.has("task_description")) task.put("task_description", params.getString("task_description").trim())
                if (params.has("schedule_type")) task.put("schedule_type", params.getString("schedule_type").trim())
                if (params.has("schedule_time")) {
                    val time = params.getString("schedule_time").trim()
                    if (!time.matches(Regex("\\d{2}:\\d{2}"))) {
                        return """{"error":"schedule_time 格式错误，应为 HH:MM"}"""
                    }
                    task.put("schedule_time", time)
                }
                if (params.has("schedule_date")) task.put("schedule_date", params.getString("schedule_date").trim())
                if (params.has("enabled")) task.put("enabled", params.optBoolean("enabled"))
                updatedTask = task
                break
            }
        }

        if (!found) return """{"error":"未找到 ID 为 $id 的定时任务"}"""

        saveTasks(core, tasks)
        triggerReschedule()

        val taskName = updatedTask?.optString("name") ?: ""
        Log.d(TAG, "更新定时任务成功: id=$id, name=$taskName")
        return JSONObject().apply {
            put("success", true)
            put("message", "定时任务已更新: $taskName")
            put("task", updatedTask)
        }.toString()
    }

    /**
     * 删除指定的定时手机任务。
     * 必须提供 id 参数。
     */
    private fun executeDelete(params: JSONObject, core: NionCore): String {
        val id = params.optString("id", "").trim()
        if (id.isEmpty()) return """{"error":"delete 操作必须提供 id"}"""

        val tasks = loadTasks(core)
        val newTasks = JSONArray()
        var deleted = false
        var deletedName = ""

        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            if (task.getString("id") == id) {
                deleted = true
                deletedName = task.optString("name", "")
                // 跳过此条（即删除）
            } else {
                newTasks.put(task)
            }
        }

        if (!deleted) return """{"error":"未找到 ID 为 $id 的定时任务"}"""

        saveTasks(core, newTasks)
        triggerReschedule()

        Log.d(TAG, "删除定时任务成功: id=$id, name=$deletedName")
        return JSONObject().apply {
            put("success", true)
            put("message", "定时任务已删除: $deletedName")
            put("deleted_id", id)
        }.toString()
    }

    /**
     * 从 settings 表加载定时任务列表。
     * key 为 [SETTING_KEY]，值为 JSON 数组。
     * 如果 key 不存在或值为空，返回空数组。
     */
    fun loadTasks(core: NionCore): JSONArray {
        val raw = core.getSetting(SETTING_KEY)
        if (raw.isNullOrBlank()) return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            Log.e(TAG, "解析定时任务列表失败，重置为空", e)
            JSONArray()
        }
    }

    /**
     * 将定时任务列表保存回 settings 表。
     */
    private fun saveTasks(core: NionCore, tasks: JSONArray) {
        core.setSetting(SETTING_KEY, tasks.toString())
    }

    /**
     * 触发调度器重注册所有闹钟。
     * 通过 NionApp.instance 获取 Context，确保闹钟状态与任务列表一致。
     */
    private fun triggerReschedule() {
        try {
            val app = NionApp.instance
            if (app != null) {
                PhoneAutomationScheduler.rescheduleAll(app, app.core)
            } else {
                Log.w(TAG, "NionApp.instance 为 null，无法触发调度器重注册")
            }
        } catch (e: Exception) {
            Log.e(TAG, "触发调度器重注册失败", e)
        }
    }
}
