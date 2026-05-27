package com.echonion.nion.reminder

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.echonion.nion.NionApp
import uniffi.nion_core.NionCore

/**
 * 提醒 Worker —— 闹钟触发后的核心处理逻辑。
 *
 * 替代 ReminderReceiver 中的直接通知逻辑，改用 WorkManager 在后台执行：
 * 1. 从 DB 读取任务详情
 * 2. 读取/递增 trigger_count
 * 3. 生成个性化文案（LLM 或模板）
 * 4. 发送带 Action 按钮的通知
 * 5. 发事件到 UI 层（前台弹窗）
 * 6. 如果未达最大次数，调度下一次循环
 * 7. 每日任务自动调度明天的闹钟
 *
 * 使用 CoroutineWorker 支持挂起函数（LLM 调用是异步的）。
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ReminderWorker"

        /** InputData key：任务 ID */
        const val KEY_TASK_ID = "task_id"
        /** InputData key：闹钟类型（"exact" 或 "daily"） */
        const val KEY_TYPE = "type"

        /**
         * 启动 ReminderWorker 执行提醒逻辑。
         * 由 ReminderReceiver 在闹钟触发时调用。
         *
         * @param context 上下文
         * @param taskId 任务 ID
         * @param type 闹钟类型（"exact" 或 "daily"）
         */
        fun enqueue(context: Context, taskId: String, type: String) {
            val data = Data.Builder()
                .putString(KEY_TASK_ID, taskId)
                .putString(KEY_TYPE, type)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "已入队 ReminderWorker: taskId=$taskId, type=$type")
        }
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val type = inputData.getString(KEY_TYPE) ?: return Result.failure()

        Log.d(TAG, "开始执行提醒逻辑: taskId=$taskId, type=$type")

        val app = applicationContext as? NionApp
        if (app == null) {
            Log.e(TAG, "无法获取 NionApp 实例")
            return Result.failure()
        }

        val core = app.core

        try {
            // 1. 从 DB 读取任务详情
            val task = core.getTask(taskId)
            val taskTitle = task.title
            val taskPriority = task.priority

            // 2. 读取并递增 trigger_count
            val triggerCount = ReminderStore.incrementTriggerCount(applicationContext, taskId)
            Log.d(TAG, "触发次数: $triggerCount/$${ReminderStore.MAX_TRIGGER_COUNT}, task=$taskTitle")

            // 3. 生成个性化文案（LLM 或模板兜底）
            val message = ReminderMessageGenerator.generateWithLLM(
                core, taskTitle, taskPriority, triggerCount,
            )

            // 4. 发送带 Action 按钮的通知
            NotificationHelper.showReminderNotification(
                applicationContext, taskId, taskTitle, message, triggerCount,
            )

            // 5. 根据 App 前后台状态选择通知方式
            if (app.isInForeground) {
                // 前台：发 SharedFlow 事件 → Compose ReminderOverlay 显示 App 内悬浮卡片
                // 同时 dismiss 系统通知，避免双重提醒
                try {
                    app.postReminderEvent(ReminderEvent(taskId, taskTitle, type, message, triggerCount))
                    Log.d(TAG, "已发送提醒事件到 UI（前台模式）")
                } catch (e: Exception) {
                    Log.w(TAG, "发送提醒事件失败", e)
                }
            } else {
                // 后台：检查是否有悬浮窗权限
                // 有权限 → 启动 ReminderFloatingService 显示系统级悬浮窗
                // 无权限 → 保留系统通知栏通知（fallback）
                val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(applicationContext)
                } else {
                    true
                }

                if (hasOverlayPermission) {
                    try {
                        ReminderFloatingService.start(
                            applicationContext, taskId, taskTitle, message, triggerCount,
                        )
                        // 悬浮窗已接管，取消系统通知避免双重提醒
                        NotificationHelper.dismissNotification(applicationContext, taskId)
                        Log.d(TAG, "已启动悬浮窗 Service（后台模式）")
                    } catch (e: Exception) {
                        Log.w(TAG, "启动悬浮窗失败，保留系统通知", e)
                    }
                } else {
                    Log.d(TAG, "App 在后台且无悬浮窗权限，保留系统通知")
                }
            }

            // 6. 如果未达最大次数，调度下一次循环
            if (triggerCount < ReminderStore.MAX_TRIGGER_COUNT) {
                val nextDelay = ReminderStore.LOOP_INTERVAL_MINUTES * 60_000L
                ReminderScheduler.scheduleSnoozeReminder(
                    applicationContext,
                    taskId,
                    ReminderStore.LOOP_INTERVAL_MINUTES.toInt(),
                )
                Log.d(TAG, "已调度下一次循环: ${ReminderStore.LOOP_INTERVAL_MINUTES} 分钟后")
            } else {
                Log.d(TAG, "已达到最大提醒次数，循环终止: taskId=$taskId")
            }

            // 7. 每日任务：自动调度明天的闹钟
            if (type == ReminderScheduler.TYPE_DAILY) {
                scheduleNextDaily(applicationContext, core, taskId)
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "提醒逻辑执行失败: taskId=$taskId", e)
            return Result.failure()
        }
    }

    /**
     * 调度每日任务的明天闹钟。
     * 从 DB 读取 recurrence_reminder_time，解析 HH:MM 后调度明天同一时刻。
     */
    private fun scheduleNextDaily(context: Context, core: NionCore, taskId: String) {
        try {
            val task = core.getTask(taskId)
            val time = task.recurrenceReminderTime ?: return
            val parts = time.split(":")
            if (parts.size == 2) {
                val hour = parts[0].toIntOrNull() ?: return
                val minute = parts[1].toIntOrNull() ?: return
                ReminderScheduler.cancelReminder(context, taskId)
                ReminderScheduler.scheduleDailyReminder(context, taskId, hour, minute)
                Log.d(TAG, "已调度明天每日提醒: taskId=$taskId, $hour:$minute")
            }
        } catch (e: Exception) {
            Log.w(TAG, "调度明天每日提醒失败: $taskId", e)
        }
    }
}
