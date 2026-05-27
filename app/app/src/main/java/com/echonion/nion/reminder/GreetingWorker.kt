package com.echonion.nion.reminder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.echonion.nion.NionApp
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
            // 1. 查询今日任务数据
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val allTasks = core.getTasks()
            val todayTasks = allTasks.filter { it.reminder?.substringBefore("T") == today }
            val completedToday = todayTasks.count { it.status == "done" }
            val pendingToday = todayTasks.count { it.status != "done" }
            val highPriorityPending = todayTasks.count { it.status != "done" && it.priority == "high" }

            // 2. 生成问候文案（注入天气上下文）
            val weatherSummary = try {
                WeatherService.fetchWeatherSummary(applicationContext, core)
            } catch (e: Exception) {
                Log.w(TAG, "获取天气数据用于问候失败", e)
                null
            }
            val greetingText = generateGreeting(
                core, type, todayTasks.map { it.title },
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
            val timeContext = when (type) {
                "morning" -> "现在是早上，新的一天开始了"
                "noon" -> "现在是中午，午饭时间"
                "evening" -> "现在是晚上，一天快结束了"
                else -> ""
            }

            val systemPrompt = """你是 Nion，用户的 AI 伙伴。$timeContext。
请给用户发一条简短的问候（2-3句话）。
规则：
- 不要用 Markdown 格式
- 不要加表情符号前缀
- 语气轻松友好
- 包含今日任务摘要和一个小建议
- ${if (weatherSummary != null) "结合天气信息给出实用建议（如带伞、穿衣、防晒等）" else "无需提及天气"}"""

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
     * 从 settings 读取，morning 类型使用自定义时间，其他使用默认值。
     */
    private fun getGreetingTime(core: NionCore, type: String): String {
        return when (type) {
            "morning" -> core.getSetting("greeting_morning_time") ?: "08:00"
            "noon" -> "12:00"
            "evening" -> "21:00"
            else -> "08:00"
        }
    }
}
