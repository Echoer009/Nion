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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 批量提醒 Worker —— 检测任务密集时段并生成汇总通知。
 *
 * 当用户在某个时间段（如 14:00-16:00）有多个任务到期时，
 * 提前 30 分钟发送汇总通知，帮助用户提前规划。
 *
 * 执行流程：
 * 1. 读取 InputData 中的时段信息和任务列表
 * 2. 构建汇总文案（LLM 或模板）
 * 3. 发送汇总通知（带「查看详情」和「调整计划」按钮）
 * 4. 「调整计划」按钮 → 打开伙伴面板，Nion 帮助重新安排
 */
class BatchReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BatchReminderWorker"

        /** InputData key：密集时段的任务 ID 列表（JSON array string） */
        const val KEY_TASK_IDS = "task_ids"
        /** InputData key：时段起始时间（HH:MM） */
        const val KEY_PERIOD_START = "period_start"
        /** InputData key：时段结束时间（HH:MM） */
        const val KEY_PERIOD_END = "period_end"

        /**
         * 入队批量提醒任务。
         */
        fun enqueue(context: Context, taskIds: List<String>, periodStart: String, periodEnd: String) {
            val data = Data.Builder()
                .putString(KEY_TASK_IDS, JSONArray(taskIds).toString())
                .putString(KEY_PERIOD_START, periodStart)
                .putString(KEY_PERIOD_END, periodEnd)
                .build()
            val request = OneTimeWorkRequestBuilder<BatchReminderWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "已入队批量提醒: ${taskIds.size} 个任务, 时段 $periodStart-$periodEnd")
        }

        /**
         * 密集时段判定阈值：
         * - 2 小时时间窗口
         * - 至少 3 个任务
         */
        private const val DENSE_WINDOW_HOURS = 2L
        private const val DENSE_THRESHOLD = 3

        /**
         * 扫描所有任务，检测密集时段，并调度批量提醒。
         * 在 rescheduleAll 时调用。
         *
         * @param context 上下文
         * @param core NionCore 实例
         */
        fun scheduleBatchReminders(context: Context, core: NionCore) {
            try {
                val now = LocalDateTime.now()
                val today = now.toLocalDate()
                val allTasks = core.getTasks().filter { it.status != "done" }
                // 只看今天有 reminder 的任务
                val todayReminders = allTasks.mapNotNull { task ->
                    val reminder = task.reminder ?: return@mapNotNull null
                    val millis = parseReminderToMillis(reminder) ?: return@mapNotNull null
                    val dateTime = java.time.Instant.ofEpochMilli(millis)
                        .atZone(ZoneId.systemDefault()).toLocalDateTime()
                    // 只看今天且未来的提醒
                    if (dateTime.toLocalDate() == today && dateTime.isAfter(now)) {
                        Triple(task.id, task.title, dateTime)
                    } else null
                }.sortedBy { it.third }

                if (todayReminders.size < DENSE_THRESHOLD) return

                // 滑动窗口检测密集时段
                for (i in todayReminders.indices) {
                    val windowStart = todayReminders[i].third
                    val windowEnd = windowStart.plusHours(DENSE_WINDOW_HOURS)
                    val inWindow = todayReminders.filter { (_, _, dt) ->
                        dt.isAfter(windowStart.minusMinutes(1)) && dt.isBefore(windowEnd.plusMinutes(1))
                    }
                    if (inWindow.size >= DENSE_THRESHOLD) {
                        // 检测到密集时段，调度提前 30 分钟的提醒
                        val alertTime = windowStart.minusMinutes(30)
                        if (alertTime.isAfter(now)) {
                            val taskIds = inWindow.map { it.first }
                            val startStr = windowStart.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
                            val endStr = windowEnd.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

                            // 使用 AlarmManager 调度一个一次性闹钟，触发时直接入队 Worker
                            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                            val triggerMillis = alertTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                            // 通过 BroadcastReceiver 延迟入队
                            val intent = Intent(context, BatchReminderTriggerReceiver::class.java).apply {
                                action = "com.echonion.nion.ACTION_BATCH_REMINDER"
                                putExtra(KEY_TASK_IDS, JSONArray(taskIds).toString())
                                putExtra(KEY_PERIOD_START, startStr)
                                putExtra(KEY_PERIOD_END, endStr)
                            }
                            val requestCode = ("batch_${windowStart.hashCode()}").hashCode() and 0x7FFFFFFF
                            val pendingIntent = PendingIntent.getBroadcast(
                                context, requestCode, intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            )
                            alarmManager.setExactAndAllowWhileIdle(
                                android.app.AlarmManager.RTC_WAKEUP,
                                triggerMillis,
                                pendingIntent,
                            )
                            Log.d(TAG, "调度批量提醒: ${inWindow.size} 个任务, 时段 $startStr-$endStr, 提醒时间 $alertTime")
                        }
                        // 跳过窗口内的任务，避免重复检测
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "扫描密集时段失败", e)
            }
        }

        private fun parseReminderToMillis(reminder: String): Long? {
            return try {
                val ldt = try {
                    LocalDateTime.parse(reminder, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                } catch (_: Exception) {
                    try {
                        LocalDateTime.parse(reminder, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    } catch (_: Exception) {
                        return try {
                            java.time.OffsetDateTime.parse(reminder).toInstant().toEpochMilli()
                        } catch (_: Exception) { null }
                    }
                }
                ldt?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            } catch (_: Exception) { null }
        }
    }

    override suspend fun doWork(): Result {
        val taskIdsJson = inputData.getString(KEY_TASK_IDS) ?: return Result.failure()
        val periodStart = inputData.getString(KEY_PERIOD_START) ?: return Result.failure()
        val periodEnd = inputData.getString(KEY_PERIOD_END) ?: return Result.failure()

        Log.d(TAG, "开始生成批量提醒: 时段 $periodStart-$periodEnd")

        val app = applicationContext as? NionApp ?: return Result.failure()
        val core = app.core

        try {
            // 解析任务 ID 列表
            val taskIdsArr = JSONArray(taskIdsJson)
            val taskIds = (0 until taskIdsArr.length()).map { taskIdsArr.getString(it) }

            // 获取任务详情
            val taskInfos = taskIds.mapNotNull { id ->
                try {
                    val task = core.getTask(id)
                    val reminderTime = task.reminder?.let { r ->
                        parseReminderToMillis(r)?.let { m ->
                            java.time.Instant.ofEpochMilli(m)
                                .atZone(ZoneId.systemDefault()).toLocalTime()
                                .format(DateTimeFormatter.ofPattern("HH:mm"))
                        }
                    } ?: ""
                    Triple(task.title, task.priority, reminderTime)
                } catch (_: Exception) { null }
            }

            // 生成汇总文案
            val message = generateBatchMessage(core, taskInfos, periodStart, periodEnd)

            // 发送通知
            showBatchNotification(applicationContext, message, taskIds)

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "批量提醒失败", e)
            return Result.failure()
        }
    }

    /**
     * 生成批量提醒文案。
     */
    private suspend fun generateBatchMessage(
        core: NionCore,
        taskInfos: List<Triple<String, String, String>>,
        periodStart: String,
        periodEnd: String,
    ): String {
        // 尝试 LLM
        val llmResult = tryBatchLLM(core, taskInfos, periodStart, periodEnd)
        if (llmResult != null) return llmResult

        // 模板兜底
        val taskLines = taskInfos.joinToString("\n") { (title, _, time) ->
            if (time.isNotEmpty()) "• $title ($time)" else "• $title"
        }
        return "${periodStart}-${periodEnd} 这段时间有点忙哦～\n你有 ${taskInfos.size} 个任务堆在一起：\n$taskLines\n建议提前规划一下顺序！"
    }

    private suspend fun tryBatchLLM(
        core: NionCore,
        taskInfos: List<Triple<String, String, String>>,
        periodStart: String,
        periodEnd: String,
    ): String? {
        try {
            val providerName = core.getSetting("llm_provider") ?: return null
            val apiKey = core.getSetting("llm_api_key") ?: return null
            val model = core.getSetting("llm_model") ?: return null
            val providerConfig = builtInProviders.find { it.name == providerName }
                ?: ProviderConfig(providerName, core.getSetting("llm_base_url") ?: "", ApiType.OPENAI_COMPATIBLE)

            val systemPrompt = "你是 Nion，用户的 AI 伙伴。用户在 $periodStart-$periodEnd 有多个任务密集到期。请发一条简短提醒（2-3句话），帮助用户规划。不要用 Markdown。"
            val taskList = taskInfos.joinToString("\n") { (title, priority, time) ->
                val p = when (priority) { "high" -> "高优"; "medium" -> "中优"; else -> "低优" }
                if (time.isNotEmpty()) "- $title ($p, $time)" else "- $title ($p)"
            }
            val userMsg = "密集时段任务：\n$taskList"

            val messages = listOf(
                JSONObject().apply { put("role", "system"); put("content", systemPrompt) },
                JSONObject().apply { put("role", "user"); put("content", userMsg) },
            )

            val result = ChatService.chatSimple(providerConfig, apiKey, model, messages)
            return result.getOrNull()?.trim()
        } catch (_: Exception) { return null }
    }

    /**
     * 显示批量提醒通知。
     */
    private fun showBatchNotification(context: Context, message: String, taskIds: List<String>) {
        val notificationId = ("batch_reminder").hashCode() and 0x7FFFFFFF

        // 点击通知 → 打开伙伴面板
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_companion", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, notificationId, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, "task_reminders")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Nion")
            .setContentText("任务密集时段提醒")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(notificationId, notification)
    }

    private fun parseReminderToMillis(reminder: String): Long? {
        return try {
            val ldt = try {
                LocalDateTime.parse(reminder, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(reminder, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                } catch (_: Exception) {
                    return try {
                        java.time.OffsetDateTime.parse(reminder).toInstant().toEpochMilli()
                    } catch (_: Exception) { null }
                }
            }
            ldt?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        } catch (_: Exception) { null }
    }
}
