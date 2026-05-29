package com.echonion.nion.reminder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.echonion.nion.NionApp
import com.echonion.nion.ui.companion.PromptDefaults
import uniffi.nion_core.NionCore
import com.echonion.nion.ui.companion.weather.WeatherService
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/**
 * 情景问候 Worker —— 在后台生成问候消息并写入伙伴对话 + 发送通知。
 *
 * 执行流程：
 * 1. 确定问候类型（morning/noon/evening）
 * 2. 查询今日任务数据（待办数、完成数、优先级分布）
 * 3. 构建上下文 → 通过 ReminderLlmClient 生成问候语（无 API key 用模板）
 * 4. 将问候消息写入当前伙伴对话（用户打开面板即可看到）
 * 5. 通过 NotificationHelper 发送系统通知
 * 6. 调度明天的同类型问候
 */
class GreetingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "GreetingWorker"
        const val KEY_GREETING_TYPE = "greeting_type"

        /**
         * 入队问候任务。
         * @param context 上下文
         * @param type 问候类型（"morning"/"noon"/"evening"）
         */
        fun enqueue(context: Context, type: String) {
            val data = Data.Builder()
                .putString(KEY_GREETING_TYPE, type)
                .build()
            val request = OneTimeWorkRequestBuilder<GreetingWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "已入队问候 Worker: type=$type")
        }
    }

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_GREETING_TYPE) ?: return Result.failure()
        Log.d(TAG, "开始生成问候: type=$type")

        val app = applicationContext as? NionApp ?: return Result.failure()
        val core = app.core

        try {
            // ── 1. 收集今日任务数据 ──
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

            // 1a. 通过 Rust API 获取"今日日程"：
            //   - reminder 日期 = 今天的普通任务
            //   - 每日循环任务（recurrence_rule='daily'，reminder=null）
            // 完成状态由 Rust 端正确判断（每日任务查 daily_completions 表，普通任务看 status）
            val todayScheduled = core.getTasksDueToday(today)

            // 1b. 补充：没有 reminder、也没有 recurrence 的未完成顶层任务
            // 这些任务不属于任何特定日期，但用户同样关心它们的存在
            val unscheduledPending = core.getTasks().filter { task ->
                task.reminder == null
                    && (task.recurrenceRule == null || task.recurrenceRule == "none")
                    && task.status != "done"
                    && task.parentId == null
            }

            // 1c. 汇总统计
            // 今日日程中已完成的数量（含每日循环任务通过 daily_completions 判断的完成数）
            val completedToday = todayScheduled.count { it.completedForDate }
            // 今日日程中未完成的数量
            val pendingScheduled = todayScheduled.count { !it.completedForDate }
            // 总未完成 = 日程未完成 + 无排期的未完成
            val pendingToday = pendingScheduled + unscheduledPending.size
            // 高优先级未完成（日程 + 无排期）
            val highPriorityPending =
                todayScheduled.count { !it.completedForDate && it.task.priority == "high" }
                    + unscheduledPending.count { it.priority == "high" }

            // 1d. 合并任务标题列表（给 LLM 和模板兜底用）
            val todayTaskTitles = todayScheduled.map { it.task.title } + unscheduledPending.map { it.title }

            // ── 2. 生成问候文案（注入天气上下文） ──
            val weatherSummary = try {
                WeatherService.fetchWeatherSummary(applicationContext, core)
            } catch (e: Exception) {
                Log.w(TAG, "获取天气数据用于问候失败", e)
                null
            }
            val greetingText = generateGreeting(
                core, type, todayTaskTitles,
                completedToday, pendingToday, highPriorityPending,
                weatherSummary,
            )

            // 3. 写入伙伴对话
            writeGreetingToConversation(core, greetingText)

            // 4. 通过 NotificationHelper 发送通知（复用共享方法）
            NotificationHelper.showGreetingNotification(applicationContext, type, greetingText)

            // 5. 调度明天的同类型问候
            val timeStr = getGreetingTime(core, type)
            GreetingScheduler.scheduleNextDay(applicationContext, type, timeStr)

            Log.d(TAG, "问候完成: type=$type")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "问候生成失败: type=$type", e)
            return Result.failure()
        }
    }

    /**
     * 生成问候文案。
     * 优先通过 ReminderLlmClient 尝试 LLM 生成，失败时使用模板兜底。
     * 会注入天气上下文，让问候结合天气情况给出建议。
     *
     * @param core NionCore 单例
     * @param type 问候类型
     * @param taskTitles 今日任务标题列表
     * @param completed 已完成数
     * @param pending 未完成数
     * @param highPriority 高优先级未完成数
     * @param weatherSummary 天气概要文本（可能为 null）
     */
    private suspend fun generateGreeting(
        core: NionCore,
        type: String,
        taskTitles: List<String>,
        completed: Int,
        pending: Int,
        highPriority: Int,
        weatherSummary: String? = null,
    ): String {
        // 尝试 LLM（通过共享客户端统一管理配置读取和调用）
        val client = ReminderLlmClient.fromCore(core)
        if (client != null) {
            // 从 settings 读取对应类型的提示词模板
            val promptKey = when (type) {
                "morning" -> PromptDefaults.KEY_GREETING_MORNING
                "noon" -> PromptDefaults.KEY_GREETING_NOON
                "evening" -> PromptDefaults.KEY_GREETING_EVENING
                else -> PromptDefaults.KEY_GREETING_MORNING
            }
            val defaultPrompt = when (type) {
                "morning" -> PromptDefaults.GREETING_MORNING
                "noon" -> PromptDefaults.GREETING_NOON
                "evening" -> PromptDefaults.GREETING_EVENING
                else -> PromptDefaults.GREETING_MORNING
            }
            val companionName = core.getSetting("companion_name") ?: "Nion"
            val promptTemplate = core.getSetting(promptKey) ?: defaultPrompt
            // 自动注入人设：先加载 prompt_persona 作为 system prompt 的前缀，
            // 然后拼接场景规则（问候模板），确保所有后台 LLM 调用都带有人设
            val persona = (core.getSetting(PromptDefaults.KEY_PERSONA) ?: PromptDefaults.PERSONA)
                .replace("{name}", companionName)
            val systemPrompt = persona + "\n\n" + promptTemplate

            val taskList = if (taskTitles.isNotEmpty()) {
                taskTitles.joinToString("\n") { "- $it" }
            } else {
                "今天没有待办任务"
            }

            val weatherLine = if (weatherSummary != null) {
                "\n当前天气：$weatherSummary"
            } else ""

            val userMsg = """用户今日任务：
$taskList
已完成：$completed 个
未完成：$pending 个
高优先级未完成：$highPriority 个$weatherLine"""

            val result = client.chat(systemPrompt, userMsg)
            if (result != null) return result
        }

        // LLM 失败或不可用，用模板兜底
        return generateFromTemplate(type, taskTitles, completed, pending, highPriority)
    }

    /**
     * 模板兜底生成问候语。
     * 天气信息不为空时，在模板末尾追加天气建议。
     */
    private fun generateFromTemplate(
        type: String,
        taskTitles: List<String>,
        completed: Int,
        pending: Int,
        highPriority: Int,
        weatherSummary: String? = null,
    ): String {
        val weatherTip = if (weatherSummary != null) {
            " $weatherSummary。"
        } else ""

        return when (type) {
            "morning" -> {
                if (pending > 0) {
                    "早安～今天有 $pending 个任务等着你${if (highPriority > 0) "，其中 $highPriority 个比较紧急" else ""}。新的一天，加油！$weatherTip"
                } else {
                    "早安～今天没有待办任务，轻松的一天！$weatherTip"
                }
            }
            "noon" -> {
                if (completed > 0 && pending > 0) {
                    "午安～上午完成了 $completed 个任务，下午还有 $pending 个。午饭后来继续吧！$weatherTip"
                } else if (pending > 0) {
                    "午安～下午还有 $pending 个任务没完成，趁着午后精力充沛搞定它们！$weatherTip"
                } else {
                    "午安～今天的任务都完成了，好好休息一下！$weatherTip"
                }
            }
            "evening" -> {
                if (completed > 0) {
                    "晚安～今天完成了 $completed 个任务，表现不错！好好休息，明天继续加油。$weatherTip"
                } else if (pending > 0) {
                    "晚安～今天还有 $pending 个任务没完成，明天再接再厉！早点休息。$weatherTip"
                } else {
                    "晚安～今天一切顺利，好好休息吧！$weatherTip"
                }
            }
            else -> "嗨～有什么需要帮忙的吗？"
        }
    }

    /**
     * 将问候消息写入当前伙伴对话。
     * 追加一条 Nion 发送的消息，用户打开面板时可以看到。
     */
    private fun writeGreetingToConversation(core: NionCore, text: String) {
        try {
            var convId = core.getSetting("current_conversation_id")
            // 如果没有当前对话，创建一个新对话
            if (convId.isNullOrEmpty()) {
                convId = java.util.UUID.randomUUID().toString()
                val title = "Nion 的问候"
                core.saveConversation(convId, title, "[]", "[]")
                core.setSetting("current_conversation_id", convId)
                Log.d(TAG, "创建新对话用于问候: convId=$convId")
            }
            // 加载现有对话
            val conv = core.getConversation(convId)
            val messagesArr = JSONArray(conv.messages)
            // 追加 Nion 的问候消息
            messagesArr.put(JSONObject().apply {
                put("id", java.util.UUID.randomUUID().toString())
                put("text", text)
                put("isFromUser", false)
                put("timestamp", java.time.LocalTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                ))
                put("isToolMessage", false)
                put("toolDone", false)
            })
            // 保存
            val apiHistoryStr = conv.apiHistory
            val title = conv.title
            core.saveConversation(convId, title, messagesArr.toString(), apiHistoryStr)
            Log.d(TAG, "问候已写入对话: convId=$convId")
        } catch (e: Exception) {
            Log.w(TAG, "写入对话失败", e)
        }
    }

    /**
     * 获取问候类型的调度时间。
     * 所有类型的时间均从 settings 读取，支持用户自定义。
     */
    private fun getGreetingTime(core: NionCore, type: String): String {
        return when (type) {
            "morning" -> core.getSetting("greeting_morning_time") ?: "08:00"
            "noon" -> core.getSetting("greeting_noon_time") ?: "12:00"
            "evening" -> core.getSetting("greeting_evening_time") ?: "21:00"
            else -> "08:00"
        }
    }
}
