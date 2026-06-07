package com.echonion.nion.reminder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
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
            // ── 0. 立即显示"正在输入..."通知，让用户知道 Nion 在工作 ──
            val companionName = core.getSetting("companion_name")
                ?: com.echonion.nion.ui.companion.PromptDefaults.DEFAULT_COMPANION_NAME
            NotificationHelper.showTypingNotification(applicationContext, "reminder_$taskId", companionName)

            // 1. 从 DB 读取任务详情
            val task = core.getTask(taskId)
            val taskTitle = task.name
            val taskPriority = task.priority

            // 2. 读取并递增 trigger_count
            val oldTriggerCount = ReminderStore.getTriggerCount(applicationContext, taskId)
            val triggerCount = ReminderStore.incrementTriggerCount(applicationContext, taskId)
            Log.d(TAG, "触发次数: $triggerCount/${ReminderStore.MAX_TRIGGER_COUNT}, task=$taskTitle (旧值=$oldTriggerCount)")

            // 3. 生成个性化文案（LLM 或模板兜底）
            val message = ReminderMessageGenerator.generateWithLLM(
                core, taskTitle, taskPriority, triggerCount,
            )

            // 4. 发送带 Action 按钮的通知
            NotificationHelper.showReminderNotification(
                applicationContext, taskId, taskTitle, message, triggerCount,
            )

            // 5. 通过 OverlayDispatcher 分发：有权限→悬浮窗 / 无权限+前台→Overlay / 兜底通知
            // 通知栏通知已在第 4 步发送，悬浮窗/Overlay 接管后会主动取消
            OverlayDispatcher.dispatch(
                context = applicationContext,
                onForeground = {
                    // 无权限 + 前台：发 SharedFlow 事件 → Compose ReminderOverlay 显示 App 内悬浮卡片
                    app.postReminderEvent(ReminderEvent(taskId, taskTitle, type, message, triggerCount, taskPriority))
                    // 前台 Overlay 已接管，取消系统通知避免双重提醒
                    NotificationHelper.dismissNotification(applicationContext, taskId)
                    Log.d(TAG, "已发送提醒事件到 UI（前台模式）")
                },
                onBackgroundOverlay = {
                    // 有悬浮窗权限（不论前后台）→ 启动 ReminderFloatingService
                    ReminderFloatingService.start(
                        applicationContext, taskId, taskTitle, message, triggerCount, taskPriority,
                    )
                    // 悬浮窗已接管，取消系统通知避免双重提醒
                    NotificationHelper.dismissNotification(applicationContext, taskId)
                    Log.d(TAG, "已启动悬浮窗 Service（模式=${if (app.isInForeground) "前台" else "后台"}）")
                },
                onFallback = {
                    // 兜底：保留系统通知（上面已发送）
                    Log.d(TAG, "App 在后台且无悬浮窗权限，保留系统通知")
                },
            )

            // "正在输入..."通知已完成使命，取消它
            NotificationHelper.dismissTypingNotification(applicationContext, "reminder_$taskId")

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
            // 异常时也要取消"正在输入"通知，避免残留
            NotificationHelper.dismissTypingNotification(applicationContext, "reminder_$taskId")
            return Result.failure()
        }
    }

    /**
     * 调度每日任务的明天闹钟。
     * 从 DB 读取 recurrence_reminder_time，解析 HH:MM 后调度明天同一时刻。
     * 如果当前任务已被标记 done（用户提前完成），会查找同名、同清单、同分组的
     * 下一天自动生成实例，为新实例注册闹钟。
     */
    private fun scheduleNextDaily(context: Context, core: NionCore, taskId: String) {
        try {
            val task = core.getTask(taskId)
            val time = task.recurrenceReminderTime ?: return
            val parts = time.split(":")
            if (parts.size != 2) return
            val hour = parts[0].toIntOrNull() ?: return
            val minute = parts[1].toIntOrNull() ?: return

            // 先取消当前任务的闹钟
            ReminderScheduler.cancelReminder(context, taskId)

            if (task.status == "done") {
                // 任务已完成（用户提前点了完成），自动生成的新实例已有不同 ID。
                // 新实例已由 TaskViewModel 在完成时注册了闹钟，此处无需重复注册。
                Log.d(TAG, "每日任务已完成，跳过调度: taskId=$taskId（新实例闹钟已由 ViewModel 注册）")
                return
            }

            // 任务未完成（闹钟正常触发），为同一任务调度明天的闹钟
            ReminderScheduler.scheduleDailyReminder(context, taskId, hour, minute)
            Log.d(TAG, "已调度明天每日提醒: taskId=$taskId, $hour:$minute")
        } catch (e: Exception) {
            Log.w(TAG, "调度明天每日提醒失败: $taskId", e)
        }
    }

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

            // 使用唯一 Work 名称去重：同一任务已有排队中的 Worker 则跳过，
            // 防止 AlarmManager 重复投递导致 trigger_count 被多次递增
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "reminder_$taskId",
                    ExistingWorkPolicy.KEEP,
                    workRequest,
                )
            Log.d(TAG, "已入队 ReminderWorker: taskId=$taskId, type=$type")
        }
    }
}
