package com.echonion.nion.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.echonion.nion.NionApp
import com.echonion.nion.R
import com.echonion.nion.ui.companion.phoneagent.AutoGLMClient
import com.echonion.nion.ui.companion.phoneagent.PhoneAgentBridge
import com.echonion.nion.ui.companion.phoneagent.PhoneAgentFloatingService
import com.echonion.nion.ui.companion.phoneagent.PhoneAgentLoop
import com.echonion.nion.ui.companion.tools.ScheduledPhoneTaskTool
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 定时手机任务 Worker —— 执行 Phone Agent 子循环并汇报结果。
 *
 * 触发流程：
 * PhoneAutomationReceiver（用户点击「执行」按钮）→ enqueue → doWork()
 *
 * 执行步骤：
 * 1. 从 settings 读取任务详情（task_description）
 * 2. 检查无障碍服务是否已启用
 * 3. 读取 Phone Agent API 配置
 * 4. 启动悬浮窗显示进度
 * 5. 运行 PhoneAgentLoop.run(taskDescription)
 * 6. 更新 last_run_at 和 last_result
 * 7. 发送结果通知（成功/失败）
 * 8. daily 类型：调度明天的闹钟
 * 9. once 类型：标记任务为禁用
 *
 * 错误处理：
 * - 无障碍服务未开启 → 直接返回失败，通知用户
 * - API 配置缺失 → 直接返回失败
 * - 执行过程中异常 → 记录错误信息，通知用户
 */
class PhoneAutomationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        Log.d(TAG, "开始执行定时手机任务: taskId=$taskId")

        val app = applicationContext as? NionApp ?: return Result.failure()
        val core = app.core

        // 1. 读取任务详情
        val task = PhoneAutomationScheduler.findTaskById(core, taskId)
        if (task == null) {
            Log.w(TAG, "未找到定时任务: taskId=$taskId")
            return Result.failure()
        }

        val taskName = task.optString("name", "定时任务")
        val taskDescription = task.optString("task_description", "")
        val scheduleType = task.optString("schedule_type", "once")
        val scheduleTime = task.optString("schedule_time", "08:00")

        if (taskDescription.isEmpty()) {
            updateTaskResult(core, taskId, false, "任务描述为空")
            return Result.failure()
        }

        // 2. 显示"正在执行..."前台通知
        showRunningNotification(applicationContext, taskId, taskName)

        // 3. 检查无障碍服务
        val accessibilityEnabled = PhoneAgentBridge.isAccessibilityServiceEnabled(app)
        if (!accessibilityEnabled) {
            val errorMsg = "无障碍服务未开启，请在系统设置→无障碍中开启 Nion Phone Agent"
            updateTaskResult(core, taskId, false, errorMsg)
            showResultNotification(applicationContext, taskId, taskName, false, errorMsg)
            dismissRunningNotification(applicationContext, taskId)
            return Result.failure()
        }

        val serviceRunning = PhoneAgentBridge.isServiceRunning()
        if (!serviceRunning) {
            val errorMsg = "Phone Agent 服务未运行，请确认无障碍服务已开启"
            updateTaskResult(core, taskId, false, errorMsg)
            showResultNotification(applicationContext, taskId, taskName, false, errorMsg)
            dismissRunningNotification(applicationContext, taskId)
            return Result.failure()
        }

        // 4. 读取 Phone Agent API 配置
        val baseUrl = readSetting(core, "phone_agent_base_url")
            ?: "https://api-inference.modelscope.cn/v1"
        val apiKey = readSetting(core, "phone_agent_api_key")
        if (apiKey == null) {
            val errorMsg = "未配置 Phone Agent API Key，请在设置中配置"
            updateTaskResult(core, taskId, false, errorMsg)
            showResultNotification(applicationContext, taskId, taskName, false, errorMsg)
            dismissRunningNotification(applicationContext, taskId)
            return Result.failure()
        }
        val model = readSetting(core, "phone_agent_model") ?: "ZhipuAI/AutoGLM-Phone-9B"

        // 5. 启动悬浮窗并运行 Phone Agent 循环
        val client = AutoGLMClient(baseUrl = baseUrl, apiKey = apiKey, model = model)
        PhoneAgentLoop.resetCancel()
        PhoneAgentFloatingService.resetState()

        // 尝试启动悬浮窗 Service（可能失败，不影响核心流程）
        try {
            PhoneAgentFloatingService.start(app)
        } catch (e: Exception) {
            Log.w(TAG, "启动悬浮窗 Service 失败，继续执行", e)
        }

        val result = try {
            val loop = PhoneAgentLoop(client = client)
            loop.run(taskDescription)
        } catch (e: Exception) {
            Log.e(TAG, "PhoneAgentLoop 执行异常: taskId=$taskId", e)
            PhoneAgentLoop.AgentResult(
                success = false,
                message = "执行异常: ${e.message}",
                totalSteps = 0,
                steps = emptyList(),
            )
        } finally {
            // 任务结束后清理悬浮窗
            try {
                PhoneAgentFloatingService.stop(app)
            } catch (_: Exception) {
                Log.w(TAG, "停止悬浮窗 Service 异常")
            }
        }

        // 6. 更新任务执行结果
        val resultMessage = if (result.success) {
            "成功（${result.totalSteps}步）: ${result.message}"
        } else {
            "失败（${result.totalSteps}步）: ${result.message}"
        }
        updateTaskResult(core, taskId, result.success, resultMessage)

        // 7. 发送结果通知
        showResultNotification(applicationContext, taskId, taskName, result.success, result.message)
        dismissRunningNotification(applicationContext, taskId)

        // 8. 调度下次执行
        if (scheduleType == "daily") {
            PhoneAutomationScheduler.scheduleNextDay(applicationContext, taskId, scheduleTime)
            Log.d(TAG, "已调度明天定时任务: taskId=$taskId, time=$scheduleTime")
        } else {
            // 一次性任务执行完后标记禁用
            disableTask(core, taskId)
            Log.d(TAG, "一次性任务已完成，已禁用: taskId=$taskId")
        }

        Log.d(TAG, "定时手机任务执行完成: taskId=$taskId, success=${result.success}")
        return Result.success()
    }

    /**
     * 更新任务的 last_run_at 和 last_result 字段。
     * 直接修改 settings 中的 JSON 数组对应元素。
     *
     * @param core NionCore 实例
     * @param taskId 任务 ID
     * @param success 是否成功
     * @param message 结果消息
     */
    private fun updateTaskResult(core: uniffi.nion_core.NionCore, taskId: String, success: Boolean, message: String) {
        try {
            val tasks = ScheduledPhoneTaskTool.loadTasks(core)
            val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            for (i in 0 until tasks.length()) {
                val task = tasks.getJSONObject(i)
                if (task.getString("id") == taskId) {
                    task.put("last_run_at", now)
                    task.put("last_result", message)
                    break
                }
            }
            core.setSetting(ScheduledPhoneTaskTool.SETTING_KEY, tasks.toString())
        } catch (e: Exception) {
            Log.e(TAG, "更新任务结果失败: taskId=$taskId", e)
        }
    }

    /**
     * 禁用指定任务（用于一次性任务执行完成后）。
     */
    private fun disableTask(core: uniffi.nion_core.NionCore, taskId: String) {
        try {
            val tasks = ScheduledPhoneTaskTool.loadTasks(core)
            for (i in 0 until tasks.length()) {
                val task = tasks.getJSONObject(i)
                if (task.getString("id") == taskId) {
                    task.put("enabled", false)
                    break
                }
            }
            core.setSetting(ScheduledPhoneTaskTool.SETTING_KEY, tasks.toString())
        } catch (e: Exception) {
            Log.e(TAG, "禁用任务失败: taskId=$taskId", e)
        }
    }

    /**
     * 显示"正在执行..."通知，让用户知道 Phone Agent 正在工作中。
     * 使用 ongoing 通知，不会自动消失。
     */
    private fun showRunningNotification(context: Context, taskId: String, taskName: String) {
        val notificationId = ("phone_auto_running:$taskId").hashCode() and 0x7FFFFFFF

        // 点击通知 → 打开 app
        val contentIntent = Intent(context, com.echonion.nion.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, PhoneAutomationReceiver.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("正在执行：$taskName")
            .setContentText("Phone Agent 正在操控手机，请勿操作...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    /**
     * 取消"正在执行..."通知。
     */
    private fun dismissRunningNotification(context: Context, taskId: String) {
        val notificationId = ("phone_auto_running:$taskId").hashCode() and 0x7FFFFFFF
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
    }

    /**
     * 显示执行结果通知。
     * 成功和失败使用不同的标题文案，帮助用户快速了解执行状态。
     *
     * @param context 上下文
     * @param taskId 任务 ID
     * @param taskName 任务名称
     * @param success 是否成功
     * @param message 结果详情
     */
    private fun showResultNotification(
        context: Context,
        taskId: String,
        taskName: String,
        success: Boolean,
        message: String,
    ) {
        val notificationId = ("phone_auto_result:$taskId").hashCode() and 0x7FFFFFFF

        // 点击通知 → 打开 app
        val contentIntent = Intent(context, com.echonion.nion.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_companion", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (success) "执行完成：$taskName" else "执行失败：$taskName"
        val priority = if (success) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH

        val notification = NotificationCompat.Builder(context, PhoneAutomationReceiver.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    /**
     * 从 NionCore settings 表中读取字符串配置。
     */
    private fun readSetting(core: uniffi.nion_core.NionCore, key: String): String? {
        return try {
            val value = core.getSetting(key)
            if (value.isNullOrBlank()) null else value
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "PhoneAutomationWorker"

        /** InputData key：定时任务 ID */
        const val KEY_TASK_ID = "automation_task_id"

        /**
         * 入队定时手机任务 Worker。
         * 由 PhoneAutomationReceiver 在用户点击「执行」按钮后调用。
         *
         * @param context 上下文
         * @param taskId 定时任务 ID
         */
        fun enqueue(context: Context, taskId: String) {
            val data = Data.Builder()
                .putString(KEY_TASK_ID, taskId)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<PhoneAutomationWorker>()
                .setInputData(data)
                .build()

            // 使用唯一 Work 名称去重：同一任务已有排队中的 Worker 则替换
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "phone_automation_$taskId",
                    ExistingWorkPolicy.REPLACE,
                    workRequest,
                )
            Log.d(TAG, "已入队定时手机任务 Worker: taskId=$taskId")
        }
    }
}
