package com.echonion.nion.reminder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.echonion.nion.MainActivity
import com.echonion.nion.NionApp
import com.echonion.nion.R
import com.echonion.nion.ui.companion.ApiType
import com.echonion.nion.ui.companion.ChatService
import com.echonion.nion.ui.companion.ProviderConfig
import com.echonion.nion.ui.companion.builtInProviders
import org.json.JSONArray
import org.json.JSONObject
import uniffi.nion_core.NionCore
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 情景问候 Worker —— 在后台生成问候消息并写入伙伴对话 + 发送通知。
 *
 * 执行流程：
 * 1. 确定问候类型（morning/noon/evening）
 * 2. 查询今日任务数据（待办数、完成数、优先级分布）
 * 3. 构建上下文 → LLM 生成问候语（无 API key 用模板）
 * 4. 将问候消息写入当前伙伴对话（用户打开面板即可看到）
 * 5. 发送系统通知（一个按钮「打开聊天」）
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
            val todayTasks = allTasks.filter { it.dueDate == today }
            val completedToday = todayTasks.count { it.status == "done" }
            val pendingToday = todayTasks.count { it.status != "done" }
            val highPriorityPending = todayTasks.count { it.status != "done" && it.priority == "high" }

            // 2. 生成问候文案
            val greetingText = generateGreeting(
                core, type, todayTasks.map { it.title },
                completedToday, pendingToday, highPriorityPending,
            )

            // 3. 写入伙伴对话
            writeGreetingToConversation(core, greetingText)

            // 4. 发送通知
            showGreetingNotification(applicationContext, type, greetingText)

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
     * 优先尝试 LLM 生成，失败时使用模板兜底。
     */
    private suspend fun generateGreeting(
        core: NionCore,
        type: String,
        taskTitles: List<String>,
        completed: Int,
        pending: Int,
        highPriority: Int,
    ): String {
        // 先尝试 LLM
        val llmResult = tryGenerateWithLLM(core, type, taskTitles, completed, pending, highPriority)
        if (llmResult != null) return llmResult

        // LLM 失败，用模板兜底
        return generateFromTemplate(type, taskTitles, completed, pending, highPriority)
    }

    /**
     * 尝试用 LLM 生成问候语。
     * @return 生成成功返回文案，失败返回 null
     */
    private suspend fun tryGenerateWithLLM(
        core: NionCore,
        type: String,
        taskTitles: List<String>,
        completed: Int,
        pending: Int,
        highPriority: Int,
    ): String? {
        try {
            val providerName = core.getSetting("llm_provider") ?: return null
            val apiKey = core.getSetting("llm_api_key") ?: return null
            val model = core.getSetting("llm_model") ?: return null
            val baseUrl = core.getSetting("llm_base_url") ?: ""
            val providerConfig = builtInProviders.find { it.name == providerName }
                ?: ProviderConfig(providerName, baseUrl, ApiType.OPENAI_COMPATIBLE)

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
- 包含今日任务摘要和一个小建议"""

            val taskList = if (taskTitles.isNotEmpty()) {
                taskTitles.joinToString("\n") { "- $it" }
            } else {
                "今天没有待办任务"
            }

            val userMsg = """用户今日任务：
$taskList
已完成：$completed 个
未完成：$pending 个
高优先级未完成：$highPriority 个"""

            val messages = listOf(
                JSONObject().apply { put("role", "system"); put("content", systemPrompt) },
                JSONObject().apply { put("role", "user"); put("content", userMsg) },
            )

            val result = ChatService.chatSimple(providerConfig, apiKey, model, messages)
            val text = result.getOrNull()
            if (!text.isNullOrBlank()) {
                return text.trim()
                    .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
                    .replace(Regex("```[\\s\\S]*?```"), "")
                    .replace("`", "")
                    .trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "LLM 问候生成失败", e)
        }
        return null
    }

    /**
     * 模板兜底生成问候语。
     */
    private fun generateFromTemplate(
        type: String,
        taskTitles: List<String>,
        completed: Int,
        pending: Int,
        highPriority: Int,
    ): String {
        return when (type) {
            "morning" -> {
                if (pending > 0) {
                    "早安～今天有 $pending 个任务等着你${if (highPriority > 0) "，其中 $highPriority 个比较紧急" else ""}。新的一天，加油！"
                } else {
                    "早安～今天没有待办任务，轻松的一天！"
                }
            }
            "noon" -> {
                if (completed > 0 && pending > 0) {
                    "午安～上午完成了 $completed 个任务，下午还有 $pending 个。午饭后来继续吧！"
                } else if (pending > 0) {
                    "午安～下午还有 $pending 个任务没完成，趁着午后精力充沛搞定它们！"
                } else {
                    "午安～今天的任务都完成了，好好休息一下！"
                }
            }
            "evening" -> {
                if (completed > 0) {
                    "晚安～今天完成了 $completed 个任务，表现不错！好好休息，明天继续加油。"
                } else if (pending > 0) {
                    "晚安～今天还有 $pending 个任务没完成，明天再接再厉！早点休息。"
                } else {
                    "晚安～今天一切顺利，好好休息吧！"
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
     * 显示问候通知（一个按钮「打开聊天」）。
     */
    private fun showGreetingNotification(context: Context, type: String, message: String) {
        val notificationId = ("greeting_$type").hashCode() and 0x7FFFFFFF

        // 点击通知 → 打开 app 并展开伙伴面板
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_companion", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (type) {
            "morning" -> "早安问候"
            "noon" -> "午间检查"
            "evening" -> "晚间总结"
            else -> "Nion"
        }

        val notification = NotificationCompat.Builder(context, "task_reminders")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(notificationId, notification)
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
